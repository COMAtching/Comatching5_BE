package com.comatching.item.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.comatching.item.global.interceptor.PaymentWebSocketHandshakeInterceptor;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class OrderWebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private static final String ADMIN_ORDER_TOPIC = "/topic/admin/orders";
	private static final String ADMIN_ROLE = "ROLE_ADMIN";

	private final PaymentWebSocketHandshakeInterceptor handshakeInterceptor;

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic");
		registry.setApplicationDestinationPrefixes("/app");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws/payment")
			.setAllowedOrigins(
				"https://comatching.site",
				"http://localhost:3000",
				"http://localhost:5173",
				"http://localhost:5500"
			)
			.addInterceptors(handshakeInterceptor)
			.withSockJS();
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(new ChannelInterceptor() {
			@Override
			public Message<?> preSend(Message<?> message, MessageChannel channel) {
				StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
				if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())
					&& ADMIN_ORDER_TOPIC.equals(accessor.getDestination())
					&& !isAdmin(accessor)) {
					throw new AccessDeniedException("Only admins can subscribe to payment order events.");
				}
				return message;
			}
		});
	}

	private boolean isAdmin(StompHeaderAccessor accessor) {
		if (accessor.getSessionAttributes() == null) {
			return false;
		}
		return ADMIN_ROLE.equals(accessor.getSessionAttributes().get("role"));
	}
}
