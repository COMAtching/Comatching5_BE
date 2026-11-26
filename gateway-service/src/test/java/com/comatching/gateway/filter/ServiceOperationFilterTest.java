package com.comatching.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

class ServiceOperationFilterTest {
	private static final String CLOSED = """
		{"feature":"LOGIN","open":false,"message":"운영 종료","version":5}
		""";

	private MockServerWebExchange run(String path, String route, HttpStatus ownerResponse, String body,
		AtomicBoolean forwarded) {
		var client = WebClient.builder().exchangeFunction(request -> {
			assertThat(request.url().getPath()).isEqualTo("/api/public/operations/LOGIN");
			return Mono.just(ClientResponse.create(ownerResponse).header("Content-Type", "application/json").body(body).build());
		});
		var gate = new ServiceOperationFilter(client, "http://owner.invalid", new ObjectMapper());
		var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
		exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, Route.async()
			.id(route).uri("http://downstream.invalid").predicate(e -> true).build());
		gate.filter(exchange, next -> { forwarded.set(true); return Mono.empty(); }).block();
		return exchange;
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/auth/signup", "/oauth2/authorization/kakao", "/api/chat/rooms",
		"/api/items/roulette", "/api/matching", "/api/v1/shop/products", "/ws/chat/info", "/api/auth/password/code"})
	void closingServiceStopsPublicAndAuthenticatedMemberActions(String path) {
		var forwarded = new AtomicBoolean();
		var exchange = run(path, "member", HttpStatus.OK, CLOSED, forwarded);
		assertThat(forwarded).isFalse();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(exchange.getResponse().getBodyAsString().block()).contains("FEATURE_CLOSED", "LOGIN");
	}

	@Test
	void operationalRoutesRetainTheirOwnAuthenticationEvenWhenTheOwnerIsUnavailable() {
		for (var route : new String[][]{{"/api/auth/login","user-service-public"},{"/api/auth/reissue","user-service-public"},
			{"/api/public/operations/stream","user-service-public"},{"/api/v1/admin/operations","admin-user-notice"},
			{"/ws/payment/info","item-service-payment-ws"}}) {
			var forwarded = new AtomicBoolean();
			run(route[0], route[1], HttpStatus.SERVICE_UNAVAILABLE, "", forwarded);
			assertThat(forwarded).isTrue();
		}
	}

	@Test
	void aCustomerRequestNeedsAnAffirmativeOpenState() {
		var forwarded = new AtomicBoolean();
		var unavailable = run("/api/chat/rooms", "chat-service", HttpStatus.SERVICE_UNAVAILABLE, "", forwarded);
		assertThat(unavailable.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(forwarded).isFalse();
		run("/api/chat/rooms", "chat-service", HttpStatus.OK, CLOSED.replace("false", "true"), forwarded);
		assertThat(forwarded).isTrue();
	}
}
