package com.comatching.chat.global.config;

import com.comatching.common.auth.SessionRedisScripts;
import com.comatching.common.auth.TrustedSession;
import com.comatching.common.socket.SocketSessionGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import com.comatching.common.operations.FeatureGate;
import com.comatching.common.operations.Feature;
import java.security.Principal;
import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import com.comatching.chat.domain.service.presence.ChatRoomPresenceTracker;
import com.comatching.chat.domain.service.chatroom.ChatRoomService;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import com.comatching.chat.global.interceptor.WebSocketHandshakeInterceptor;
import com.comatching.chat.global.security.StompPrincipal;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final WebSocketHandshakeInterceptor handshakeInterceptor;
	private final ChatRoomService chatRoomService;

	@Bean
	static SocketSessionGuard chatSessionGuard(StringRedisTemplate redis, FeatureGate features, RedisMessageListenerContainer listener) {
		var guard = new SocketSessionGuard(() -> features.state(Feature.CHAT).open(),
			identity -> SessionRedisScripts.isActive(redis, identity.login()));
		listener.addMessageListener(guard, new ChannelTopic(TrustedSession.REVOCATIONS));
		return guard;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// 연결(Handshake) 엔드포인트 설정

		// 프론트엔드에서 맨 처음 연결할 때 "ws://localhost:8080/ws/chat"으로 요청을 보냄
		registry.addEndpoint("/ws/chat")
			.setAllowedOrigins(
				"https://comatching.site",
				"http://localhost:3000",
				"http://localhost:5173",
				"http://localhost:5500"
			)
			.addInterceptors(handshakeInterceptor)
			.setHandshakeHandler(new DefaultHandshakeHandler() {
				@Override
				protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
					Map<String, Object> attributes) {

					Long memberId = (Long)attributes.get("memberId");
					if (memberId == null)
						return null;
					return new StompPrincipal(String.valueOf(memberId));
				}
			})
			.withSockJS();
		// 혹시 브라우저가 WebSocket을 지원하지 않으면,
		// HTTP Polling 방식으로라도 비슷하게 동작하게 해주는 안전장치
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(new ChannelInterceptor() {
			@Override
			public Message<?> preSend(Message<?> message, MessageChannel channel) {
				var frame = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
				if (frame == null) return message;
				var command = frame.getCommand();
				String destination = frame.getDestination();
				if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
					var attrs = frame.getSessionAttributes();
					if (attrs == null || !(attrs.get("memberId") instanceof Long id)) return null;
					frame.setUser(new StompPrincipal(id.toString()));
				} else if (command == StompCommand.SUBSCRIBE) {
					if (frame.getUser() == null) return null;
					if ("/user/queue/chat.visibility".equals(destination)) return message;
					String prefix = ChatRoomPresenceTracker.CHAT_ROOM_DESTINATION;
					String legacyPrefix = "/topic/chat.room.";
					if (destination != null && destination.startsWith(legacyPrefix)) {
						destination = prefix + destination.substring(legacyPrefix.length());
						frame.setDestination(destination);
					}
					if (destination == null || !destination.startsWith(prefix)) return null;
					chatRoomService.validateRoomMember(destination.substring(prefix.length()),
						Long.valueOf(frame.getUser().getName()));
				} else if (command == StompCommand.SEND && !"/app/chat/message".equals(destination)) {
					return null;
				}
				return message;
			}
		});
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/queue");
		registry.setApplicationDestinationPrefixes("/app");
	}
}
