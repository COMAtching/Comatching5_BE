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
import com.comatching.common.auth.LoginSession;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import java.util.UUID;
import com.comatching.gateway.auth.GatewaySessions;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;

class AuthorizationHeaderFilterTest {

	private static final String SECRET = Base64.getEncoder()
		.encodeToString("01234567890123456789012345678912".getBytes(StandardCharsets.UTF_8));

	private final LoginSession session = new LoginSession(42L, 0, UUID.randomUUID());
	private final JwtUtil jwtUtil = new JwtUtil(SECRET, 3600000L, 3600000L);
	private final GatewaySessions sessions = mock(GatewaySessions.class);
	private final AuthorizationHeaderFilter filter = new AuthorizationHeaderFilter(
		jwtUtil,
		sessions,
		new ObjectMapper()
	);

	@BeforeEach
	void setUp() {
		given(sessions.isActive(any())).willReturn(Mono.just(true));
	}

	@Test
	void shouldReplaceSpoofedMemberHeadersAndRemoveInternalToken() {
		String token = jwtUtil.createAccessToken(session, "real@example.com", MemberRole.ROLE_USER, MemberStatus.ACTIVE, "real-nickname");
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
	void shouldRejectInactiveSession() {
		String token = jwtUtil.createAccessToken(session, "real@example.com", MemberRole.ROLE_USER, MemberStatus.ACTIVE, "real-nickname");
		given(sessions.isActive(session)).willReturn(Mono.just(false));
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
	void shouldRejectLegacyTokenWithoutSessionClaims() {
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

		assertThat(chainCalled).isFalse();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void sessionStoreFailureReturnsUnavailableBeforeRouting() {
		given(sessions.isActive(session)).willReturn(Mono.error(new IllegalStateException("session store unavailable")));
		var token = jwtUtil.createAccessToken(session, "member@example.invalid", MemberRole.ROLE_USER, MemberStatus.ACTIVE, null);
		var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/items").cookie(new HttpCookie("accessToken", token)));
		filter.apply(new AuthorizationHeaderFilter.Config()).filter(exchange, ignored -> Mono.error(new AssertionError("unexpected route"))).block();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
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
