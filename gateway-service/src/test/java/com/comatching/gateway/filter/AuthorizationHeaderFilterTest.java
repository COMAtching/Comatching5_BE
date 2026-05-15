package com.comatching.gateway.filter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.comatching.common.util.JwtUtil;
import com.comatching.gateway.auth.AccessTokenDenylistService;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;

class AuthorizationHeaderFilterTest {

	private static final String SECRET = Base64.getEncoder()
		.encodeToString("01234567890123456789012345678912".getBytes(StandardCharsets.UTF_8));

	private final JwtUtil jwtUtil = new JwtUtil(SECRET, 3600000L, 3600000L);
	private final AccessTokenDenylistService accessTokenDenylistService = mock(AccessTokenDenylistService.class);
	private final AuthorizationHeaderFilter filter = new AuthorizationHeaderFilter(
		jwtUtil,
		accessTokenDenylistService,
		new ObjectMapper()
	);

	@BeforeEach
	void setUp() {
		given(accessTokenDenylistService.isDenied(any())).willReturn(Mono.just(false));
	}

	@Test
	void shouldReplaceSpoofedMemberHeadersAndRemoveInternalToken() {
		String token = jwtUtil.createAccessToken(42L, "real@example.com", "ROLE_USER", "ACTIVE", "real-nickname");
		MockServerHttpRequest request = MockServerHttpRequest.get("/api/items")
			.cookie(new HttpCookie("accessToken", token))
			.header("X-Member-Id", "999")
			.header("X-Member-Email", "spoof@example.com")
			.header("X-Member-Role", "ROLE_ADMIN")
			.header("X-Member-Nickname", "spoof")
			.header("X-Internal-Token", "spoof-token")
			.build();
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		AtomicReference<ServerHttpRequest> capturedRequest = new AtomicReference<>();
		GatewayFilterChain chain = chainExchange -> {
			capturedRequest.set(chainExchange.getRequest());
			return Mono.empty();
		};

		filter.apply(new AuthorizationHeaderFilter.Config()).filter(exchange, chain).block();

		ServerHttpRequest mutatedRequest = capturedRequest.get();
		assertThat(mutatedRequest.getHeaders().getFirst("X-Member-Id")).isEqualTo("42");
		assertThat(mutatedRequest.getHeaders().getFirst("X-Member-Email")).isEqualTo("real@example.com");
		assertThat(mutatedRequest.getHeaders().getFirst("X-Member-Role")).isEqualTo("ROLE_USER");
		assertThat(mutatedRequest.getHeaders()).doesNotContainKey("X-Internal-Token");
		assertThat(mutatedRequest.getHeaders().get("X-Member-Id")).containsExactly("42");
	}

	@Test
	void shouldRejectMissingAccessToken() {
		MockServerWebExchange exchange = MockServerWebExchange.from(
			MockServerHttpRequest.get("/api/items").build()
		);
		AtomicBoolean chainCalled = new AtomicBoolean(false);
		GatewayFilterChain chain = chainExchange -> {
			chainCalled.set(true);
			return Mono.empty();
		};

		filter.apply(new AuthorizationHeaderFilter.Config()).filter(exchange, chain).block();

		assertThat(chainCalled).isFalse();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void shouldRejectDenylistedAccessToken() {
		String token = jwtUtil.createAccessToken(42L, "real@example.com", "ROLE_USER", "ACTIVE", "real-nickname");
		String tokenId = jwtUtil.parseToken(token).getId();
		given(accessTokenDenylistService.isDenied(tokenId)).willReturn(Mono.just(true));
		MockServerWebExchange exchange = MockServerWebExchange.from(
			MockServerHttpRequest.get("/api/items")
				.cookie(new HttpCookie("accessToken", token))
				.build()
		);
		AtomicBoolean chainCalled = new AtomicBoolean(false);
		GatewayFilterChain chain = chainExchange -> {
			chainCalled.set(true);
			return Mono.empty();
		};

		filter.apply(new AuthorizationHeaderFilter.Config()).filter(exchange, chain).block();

		assertThat(chainCalled).isFalse();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void shouldAllowLegacyTokenWithoutJti() {
		String token = createLegacyAccessTokenWithoutJti();
		MockServerWebExchange exchange = MockServerWebExchange.from(
			MockServerHttpRequest.get("/api/items")
				.cookie(new HttpCookie("accessToken", token))
				.build()
		);
		AtomicBoolean chainCalled = new AtomicBoolean(false);
		GatewayFilterChain chain = chainExchange -> {
			chainCalled.set(true);
			return Mono.empty();
		};

		filter.apply(new AuthorizationHeaderFilter.Config()).filter(exchange, chain).block();

		assertThat(chainCalled).isTrue();
	}

	private String createLegacyAccessTokenWithoutJti() {
		SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
		Date now = new Date();
		return Jwts.builder()
			.setSubject("42")
			.setIssuedAt(now)
			.setExpiration(new Date(now.getTime() + 3600000L))
			.claim("email", "real@example.com")
			.claim("role", "ROLE_USER")
			.claim("status", "ACTIVE")
			.claim("nickname", "real-nickname")
			.signWith(key, SignatureAlgorithm.HS256)
			.compact();
	}
}
