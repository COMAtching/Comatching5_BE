package com.comatching.chat.global.interceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

	@org.springframework.beans.factory.annotation.Value("${internal.service-token:}") private String gatewaySecret;

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
		Map<String, Object> attributes) throws Exception {

		String role = request.getHeaders().getFirst("X-Member-Role");
		String rawNickname = request.getHeaders().getFirst("X-Member-Nickname");

		var identity = com.comatching.common.auth.TrustedSession.from(request.getHeaders(), gatewaySecret);
		if (identity == null) {
			log.error("WebSocket Handshake Failed: No Member ID");
			return false;
		}

		// WebSocket 세션 속성(attributes)에 저장
		attributes.put(com.comatching.common.socket.SocketSessionGuard.ATTRIBUTE, identity);
		attributes.put("memberId", identity.login().memberId());
		attributes.put("role", role);
		if (rawNickname != null) {
			String decodedNickname = URLDecoder.decode(rawNickname, StandardCharsets.UTF_8);
			attributes.put("nickname", decodedNickname);
		}

		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
		Exception exception) {

	}
}
