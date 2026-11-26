package com.comatching.item.global.interceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

class PaymentWebSocketHandshakeInterceptorTest {

	private final PaymentWebSocketHandshakeInterceptor interceptor = new PaymentWebSocketHandshakeInterceptor();

	@org.junit.jupiter.api.BeforeEach
	void setup() {
		org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "gatewaySecret", "test-proof");
	}

	@Test
	void shouldAllowAdminHandshake() {
		Map<String, Object> attributes = new HashMap<>();

		boolean result = interceptor.beforeHandshake(
			requestWithRole("1", "ROLE_ADMIN"),
			mock(ServerHttpResponse.class),
			mock(WebSocketHandler.class),
			attributes
		);

		assertThat(result).isTrue();
		assertThat(attributes).containsEntry("memberId", "1");
		assertThat(attributes).containsEntry("role", "ROLE_ADMIN");
	}

	@Test
	void shouldRejectNonAdminHandshake() {
		Map<String, Object> attributes = new HashMap<>();

		boolean result = interceptor.beforeHandshake(
			requestWithRole("1", "ROLE_USER"),
			mock(ServerHttpResponse.class),
			mock(WebSocketHandler.class),
			attributes
		);

		assertThat(result).isFalse();
		assertThat(attributes).isEmpty();
	}

	private ServerHttpRequest requestWithRole(String memberId, String role) {
		HttpHeaders headers = new HttpHeaders();
		headers.add("X-Member-Id", memberId);
		headers.add("X-Member-Role", role);
		new com.comatching.common.auth.TrustedSession(new com.comatching.common.auth.LoginSession(Long.parseLong(memberId), 1,
			java.util.UUID.randomUUID()), java.time.Instant.now().plusSeconds(60)).write(headers, "test-proof");
		ServerHttpRequest request = mock(ServerHttpRequest.class);
		given(request.getHeaders()).willReturn(headers);
		return request;
	}
}
