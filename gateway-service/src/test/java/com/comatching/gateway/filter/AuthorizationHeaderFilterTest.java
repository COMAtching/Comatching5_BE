package com.comatching.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.comatching.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

class AuthorizationHeaderFilterTest {

	private static final String SECRET_KEY =
		"c2lsdmVybmluZS10ZWNoLXNwcmluZy1ib290LWp3dC10dXRvcmlhbC1zZWNyZXQtc2lsdmVybmluZS10ZWNoLXNwcmluZy1ib290LWp3dC10dXRvcmlhbC1zZWNyZXQK";

	private final JwtUtil jwtUtil = new JwtUtil(SECRET_KEY, 3600000L, 3600000L);
	private final AuthorizationHeaderFilter filter = new AuthorizationHeaderFilter(jwtUtil, new ObjectMapper());

	@Test
	void shouldReplaceClientMemberHeadersWithTokenClaims() {
		String accessToken = jwtUtil.createAccessToken(
			1L,
			"admin@comatching.com",
			"ROLE_ADMIN",
			"ACTIVE",
			"관리자"
		);
		MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/admin/users")
			.cookie(new HttpCookie("accessToken", accessToken))
			.header("X-Member-Id", "999")
			.header("X-Member-Email", "user@comatching.com")
			.header("X-Member-Role", "ROLE_USER")
			.header("X-Member-Nickname", "stale")
			.build();
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		AtomicReference<ServerHttpRequest> downstreamRequest = new AtomicReference<>();
		GatewayFilter gatewayFilter = filter.apply(new AuthorizationHeaderFilter.Config());

		gatewayFilter.filter(exchange, filteredExchange -> {
			downstreamRequest.set(filteredExchange.getRequest());
			return Mono.empty();
		}).block();

		ServerHttpRequest mutatedRequest = downstreamRequest.get();
		assertThat(mutatedRequest.getHeaders().get("X-Member-Id")).containsExactly("1");
		assertThat(mutatedRequest.getHeaders().get("X-Member-Email")).containsExactly("admin@comatching.com");
		assertThat(mutatedRequest.getHeaders().get("X-Member-Role")).containsExactly("ROLE_ADMIN");
		assertThat(mutatedRequest.getHeaders().get("X-Member-Nickname")).containsExactly(
			"%EA%B4%80%EB%A6%AC%EC%9E%90"
		);
	}
}
