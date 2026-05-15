package com.comatching.item.global.interceptor;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PaymentWebSocketHandshakeInterceptor implements HandshakeInterceptor {

	private static final String ADMIN_ROLE = "ROLE_ADMIN";

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
		Map<String, Object> attributes) {

		String memberId = request.getHeaders().getFirst("X-Member-Id");
		String role = request.getHeaders().getFirst("X-Member-Role");

		if (!ADMIN_ROLE.equals(role)) {
			log.warn("Payment WebSocket handshake rejected. memberId={}, role={}", memberId, role);
			return false;
		}

		attributes.put("memberId", memberId);
		attributes.put("role", role);
		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
		Exception exception) {
	}
}
