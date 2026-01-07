package com.comatching.gateway.filter;

import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.code.ErrorCode;
import com.comatching.common.util.JwtUtil;
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
	private final ObjectMapper objectMapper;

	public AuthorizationHeaderFilter(JwtUtil jwtUtil, ObjectMapper objectMapper) {
		super(Config.class);
		this.jwtUtil = jwtUtil;
		this.objectMapper = objectMapper;
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

			// 토큰 유효성 검증
			if (!jwtUtil.validateToken(accessToken)) {
				return onError(exchange, GatewayErrorCode.TOKEN_INVALID);
			}

			// 사용자 정보 추출 및 헤더 주입
			try {
				Claims claims = jwtUtil.parseToken(accessToken);
				ServerHttpRequest modifiedRequest = request.mutate()
					.header("X-Member-Id", claims.getSubject())
					.header("X-Member-Email", claims.get("email", String.class))
					.header("X-Member-Role", claims.get("role", String.class))
					.build();

				return chain.filter(exchange.mutate().request(modifiedRequest).build());
			} catch (Exception e) {
				log.error("Token parsing error: {}", e.getMessage());
				return onError(exchange, GatewayErrorCode.TOKEN_INVALID);
			}
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