package com.comatching.gateway.filter;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.operations.Feature;
import com.comatching.common.operations.FeatureState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/** Administrative routes retain their own role/session guards while member traffic is closed. */
@Component
public class ServiceOperationFilter implements GlobalFilter, Ordered {
	private static final Set<String> ADMIN_ROUTES = Set.of("admin-user-notice", "admin-payment-product-items",
		"item-service-admin-monitor", "item-service-payment-ws");
	private static final Set<String> CONTROL_PATHS = Set.of("/api/auth/login", "/api/auth/session",
		"/api/auth/logout", "/api/auth/reissue", "/api/public/operations", "/api/public/operations/stream",
		"/api/public/operations/LOGIN", "/api/public/operations/CHAT", "/api/public/operations/MATCHING",
		"/api/public/operations/SHOP_PAYMENT");
	private final WebClient owner;
	private final ObjectMapper json;

	public ServiceOperationFilter(WebClient.Builder builder, @Value("${user-service.url:http://localhost:9000}") String url,
		ObjectMapper json) {
		this.owner = builder.baseUrl(url).build();
		this.json = json;
	}

	@Override
	public int getOrder() { return -5; }

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
		String path = exchange.getRequest().getPath().pathWithinApplication().value();
		if ((route != null && ADMIN_ROUTES.contains(route.getId())) || CONTROL_PATHS.contains(path)) {
			return chain.filter(exchange);
		}
		return owner.get().uri("/api/public/operations/LOGIN").retrieve().bodyToMono(FeatureState.class)
			.filter(s -> s.feature() == Feature.LOGIN)
			.timeout(Duration.ofSeconds(2)).onErrorResume(e -> Mono.empty())
			.map(Optional::of).defaultIfEmpty(Optional.empty())
			.flatMap(result -> {
				if (result.isEmpty()) return reject(exchange, GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE, null);
				var master = result.get();
				return master.open() ? chain.filter(exchange) : reject(exchange, GeneralErrorCode.FEATURE_CLOSED, master);
			});
	}

	private Mono<Void> reject(ServerWebExchange exchange, GeneralErrorCode code, FeatureState state) {
		var response = exchange.getResponse();
		response.setStatusCode(code.getHttpStatus());
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		response.getHeaders().setCacheControl("no-store");
		try {
			var bytes = json.writeValueAsBytes(ApiResponse.errorResponse(code, state));
			return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
		} catch (JsonProcessingException e) {
			return Mono.error(e);
		}
	}
}
