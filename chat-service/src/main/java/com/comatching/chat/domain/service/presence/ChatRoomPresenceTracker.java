package com.comatching.chat.domain.service.presence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * 이 인스턴스에 붙어 있는 WebSocket 세션이 어느 채팅방 토픽을 구독 중인지 기억한다.
 *
 * 읽음 처리는 원래 클라이언트가 방 입장 때 보내는 READ 한 번에만 의존했다.
 * 방을 켜둔 채로 받은 메시지는 아무도 읽음 처리하지 않아 "1" 이 영원히 남았다.
 * 이 추적기가 있으면 메시지를 뿌리는 시점에 수신자가 그 방을 보고 있는지 판단해
 * 서버가 대신 읽음 처리할 수 있다.
 *
 * 인스턴스별 메모리라는 점이 핵심이다. 메시지는 Redis 를 거쳐 모든 인스턴스에
 * 도착하므로, 각 인스턴스는 자기에게 붙은 세션만 책임지면 수평 확장에도 맞다.
 */
@Component
public class ChatRoomPresenceTracker {

	private static final String CHAT_ROOM_TOPIC_PREFIX = "/topic/chat.room.";

	private record RoomSubscription(String roomId, Long memberId) {
	}

	// sessionId -> (subscriptionId -> 구독 정보). 구독 해지 이벤트에는 destination 이
	// 없고 subscriptionId 만 오므로 이 형태로 들고 있어야 지울 수 있다.
	private final Map<String, Map<String, RoomSubscription>> subscriptionsBySession = new ConcurrentHashMap<>();

	@EventListener
	public void onSubscribe(SessionSubscribeEvent event) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

		String destination = accessor.getDestination();
		if (destination == null || !destination.startsWith(CHAT_ROOM_TOPIC_PREFIX)) {
			return;
		}

		Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
		Object memberId = (sessionAttributes == null) ? null : sessionAttributes.get("memberId");
		String sessionId = accessor.getSessionId();
		String subscriptionId = accessor.getSubscriptionId();
		if (!(memberId instanceof Long) || sessionId == null || subscriptionId == null) {
			return;
		}

		String roomId = destination.substring(CHAT_ROOM_TOPIC_PREFIX.length());
		subscriptionsBySession
			.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>())
			.put(subscriptionId, new RoomSubscription(roomId, (Long)memberId));
	}

	@EventListener
	public void onUnsubscribe(SessionUnsubscribeEvent event) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
		String sessionId = accessor.getSessionId();
		String subscriptionId = accessor.getSubscriptionId();
		if (sessionId == null || subscriptionId == null) {
			return;
		}

		Map<String, RoomSubscription> sessionSubscriptions = subscriptionsBySession.get(sessionId);
		if (sessionSubscriptions != null) {
			sessionSubscriptions.remove(subscriptionId);
		}
	}

	@EventListener
	public void onDisconnect(SessionDisconnectEvent event) {
		subscriptionsBySession.remove(event.getSessionId());
	}

	public boolean isViewing(String roomId, Long memberId) {
		return subscriptionsBySession.values().stream()
			.flatMap(sessionSubscriptions -> sessionSubscriptions.values().stream())
			.anyMatch(subscription ->
				subscription.roomId().equals(roomId) && subscription.memberId().equals(memberId));
	}
}
