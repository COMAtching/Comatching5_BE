package com.comatching.chat.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.comatching.chat.global.interceptor.WebSocketHandshakeInterceptor;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final WebSocketHandshakeInterceptor handshakeInterceptor;

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// 연결(Handshake) 엔드포인트 설정

		// 프론트엔드에서 맨 처음 연결할 때 "ws://localhost:9003/ws/chat"으로 요청을 보냄
		registry.addEndpoint("/ws/chat")
			.setAllowedOrigins("http://localhost:5500") // CORS 설정: 모든 도메인에서 접속 허용
			.addInterceptors(handshakeInterceptor)
			.withSockJS();
			// 혹시 브라우저가 WebSocket을 지원하지 않으면(구형 IE 등),
			// HTTP Polling 방식으로라도 비슷하게 동작하게 해주는 안전장치
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		// 메시지 브로커 설정 (우체국 역할)

		// 구독(Subscribe) 경로: "/topic"으로 시작하는 주소는 구독자들에게 바로 뿌림
		// 예: "/topic/chat.room.1" -> 1번 방에 있는 사람들에게 방송
		registry.enableSimpleBroker("/topic");

		// 발행(Publish) 경로: "/app"으로 시작하는 주소는 Controller가 받아서 처리
		// 예: 클라이언트가 "/app/chat/message"로 보내면 -> @MessageMapping이 달린 메소드로 이동
		registry.setApplicationDestinationPrefixes("/app");
	}
}
