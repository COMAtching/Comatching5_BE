package com.comatching.chat.global.interceptor;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class WebSocketHandshakeInterceptor implements HandshakeInterceptor {

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
		Map<String, Object> attributes) throws Exception {

		String memberId = request.getHeaders().getFirst("X-Member-Id");
		String role = request.getHeaders().getFirst("X-Member-Role");

		if (memberId == null) {
			return true; // 운영 환경에선 여기서 return false;
		}

		// WebSocket 세션 속성(attributes)에 저장
		attributes.put("memberId", Long.valueOf(memberId));
		attributes.put("role", role);

		log.info("WebSocket Handshake - MemberId: {}, Role: {}", memberId, role);
		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
		Exception exception) {

	}
}
