package com.comatching.gateway.filter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.TrustedSession;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.code.ErrorCode;
import com.comatching.common.util.JwtUtil;
import com.comatching.gateway.auth.GatewaySessions;
import com.comatching.gateway.exception.GatewayErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizationHeaderFilter extends AbstractGatewayFilterFactory<AuthorizationHeaderFilter.Config> {

	private final JwtUtil jwtUtil;
	private final GatewaySessions sessions;
	private final ObjectMapper objectMapper;
	private final String gatewaySecret;

	public AuthorizationHeaderFilter(
		JwtUtil jwtUtil,
		GatewaySessions sessions,
		ObjectMapper objectMapper,
		@org.springframework.beans.factory.annotation.Value("${internal.service-token:${INTERNAL_SERVICE_TOKEN:}}") String gatewaySecret
	) {
		super(Config.class);
		this.jwtUtil = jwtUtil;
		this.sessions = sessions;
		this.objectMapper = objectMapper;
		this.gatewaySecret = gatewaySecret;
	}

	public static class Config {
	}

	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> {
			ServerHttpRequest request = exchange.getRequest();

			// 토큰 추출
			String accessToken = extractToken(request);
			if (accessToken == null) {
				return onError(exchange, GatewayErrorCode.TOKEN_MISSING);
			}

			JwtUtil.VerifiedToken token;
			try {
				token = jwtUtil.readAccess(accessToken);
			} catch (BusinessException invalid) {
				return onError(exchange, GatewayErrorCode.TOKEN_INVALID);
			}
			String expected = request.getHeaders().getFirst(LoginSession.EXPECTED_HEADER);
			if (expected != null && !token.session().clientMarker().equals(expected)) {
				return onError(exchange, GeneralErrorCode.FORBIDDEN);
			}
			Claims claims = token.claims();
			return sessions.isActive(token.session())
				.map(active -> active ? HttpStatus.OK : HttpStatus.UNAUTHORIZED)
				.doOnError(e -> log.error("Session lookup failed", e))
				.onErrorReturn(HttpStatus.SERVICE_UNAVAILABLE)
				.flatMap(status -> {
					if (status == HttpStatus.SERVICE_UNAVAILABLE) return onError(exchange, GatewayErrorCode.SESSION_UNAVAILABLE);
					if (status != HttpStatus.OK) return onError(exchange, GatewayErrorCode.TOKEN_INVALID);
					ServerHttpRequest.Builder builder = request.mutate();
					builder.headers(headers -> {
						headers.remove("X-Internal-Token");
						headers.remove("X-Member-Nickname");
						headers.set("X-Member-Id", claims.getSubject());
						headers.set("X-Member-Email", claims.get("email", String.class));
						headers.set("X-Member-Role", claims.get("role", String.class));
						String nickname = claims.get("nickname", String.class);
						if (nickname != null && !nickname.isBlank()) {
							headers.set("X-Member-Nickname", URLEncoder.encode(nickname, StandardCharsets.UTF_8));
						}
						new TrustedSession(token.session(), claims.getExpiration().toInstant()).write(headers, gatewaySecret);
					});
					return chain.filter(exchange.mutate().request(builder.build()).build());
				});
		};
	}

	private String extractToken(ServerHttpRequest request) {
		HttpCookie cookie = request.getCookies().getFirst("accessToken");
		if (cookie != null) {
			return cookie.getValue();
		}
		return null;
	}

	private Mono<Void> onError(ServerWebExchange exchange, ErrorCode errorCode) {
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(errorCode.getHttpStatus());
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

		ApiResponse<Object> apiResponse = ApiResponse.errorResponse(errorCode);

		try {
			byte[] bytes = objectMapper.writeValueAsBytes(apiResponse);
			DataBuffer buffer = response.bufferFactory().wrap(bytes);
			return response.writeWith(Mono.just(buffer));
		} catch (JsonProcessingException e) {
			return response.setComplete();
		}
	}
}
