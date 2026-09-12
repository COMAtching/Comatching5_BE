package com.comatching.chat.domain.service.presence;

import static org.assertj.core.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

class ChatRoomPresenceTrackerTest {

	private static final String ROOM_ID = "room-1";
	private static final Long MEMBER_ID = 1L;

	private ChatRoomPresenceTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new ChatRoomPresenceTracker();
	}

	@Test
	@DisplayName("채팅방 토픽을 구독하면 그 방을 보고 있는 것으로 기록한다")
	void subscribe_marksMemberAsViewing() {
		tracker.onSubscribe(subscribeEvent("session-1", "sub-1", "/topic/chat.room." + ROOM_ID, MEMBER_ID));

		assertThat(tracker.isViewing(ROOM_ID, MEMBER_ID)).isTrue();
	}

	@Test
	@DisplayName("구독한 적 없으면 보고 있지 않다")
	void isViewing_falseByDefault() {
		assertThat(tracker.isViewing(ROOM_ID, MEMBER_ID)).isFalse();
	}

	@Test
	@DisplayName("채팅방 토픽이 아닌 구독은 무시한다")
	void subscribe_ignoresNonChatRoomDestinations() {
		tracker.onSubscribe(subscribeEvent("session-1", "sub-1", "/user/queue/errors", MEMBER_ID));

		assertThat(tracker.isViewing(ROOM_ID, MEMBER_ID)).isFalse();
	}

	@Test
	@DisplayName("구독을 해지하면 더 이상 보고 있지 않다")
	void unsubscribe_removesViewing() {
		tracker.onSubscribe(subscribeEvent("session-1", "sub-1", "/topic/chat.room." + ROOM_ID, MEMBER_ID));

		tracker.onUnsubscribe(unsubscribeEvent("session-1", "sub-1"));

		assertThat(tracker.isViewing(ROOM_ID, MEMBER_ID)).isFalse();
	}

	@Test
	@DisplayName("연결이 끊기면 그 세션의 모든 구독이 사라진다")
	void disconnect_removesAllSubscriptionsOfSession() {
		tracker.onSubscribe(subscribeEvent("session-1", "sub-1", "/topic/chat.room." + ROOM_ID, MEMBER_ID));
		tracker.onSubscribe(subscribeEvent("session-1", "sub-2", "/topic/chat.room.room-2", MEMBER_ID));

		tracker.onDisconnect(disconnectEvent("session-1"));

		assertThat(tracker.isViewing(ROOM_ID, MEMBER_ID)).isFalse();
		assertThat(tracker.isViewing("room-2", MEMBER_ID)).isFalse();
	}

	@Test
	@DisplayName("같은 방을 두 세션으로 보다가 한 세션만 끊기면 여전히 보고 있다")
	void disconnect_keepsViewingWhileAnotherSessionRemains() {
		tracker.onSubscribe(subscribeEvent("session-1", "sub-1", "/topic/chat.room." + ROOM_ID, MEMBER_ID));
		tracker.onSubscribe(subscribeEvent("session-2", "sub-1", "/topic/chat.room." + ROOM_ID, MEMBER_ID));

		tracker.onDisconnect(disconnectEvent("session-1"));

		assertThat(tracker.isViewing(ROOM_ID, MEMBER_ID)).isTrue();
	}

	@Test
	@DisplayName("memberId 없는 구독은 기록하지 않는다")
	void subscribe_ignoresSessionWithoutMemberId() {
		tracker.onSubscribe(subscribeEvent("session-1", "sub-1", "/topic/chat.room." + ROOM_ID, null));

		assertThat(tracker.isViewing(ROOM_ID, MEMBER_ID)).isFalse();
	}

	private SessionSubscribeEvent subscribeEvent(String sessionId, String subId, String destination, Long memberId) {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
		accessor.setSessionId(sessionId);
		accessor.setSubscriptionId(subId);
		accessor.setDestination(destination);
		Map<String, Object> attributes = new HashMap<>();
		if (memberId != null) {
			attributes.put("memberId", memberId);
		}
		accessor.setSessionAttributes(attributes);
		Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
		return new SessionSubscribeEvent(this, message);
	}

	private SessionUnsubscribeEvent unsubscribeEvent(String sessionId, String subId) {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
		accessor.setSessionId(sessionId);
		accessor.setSubscriptionId(subId);
		Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
		return new SessionUnsubscribeEvent(this, message);
	}

	private SessionDisconnectEvent disconnectEvent(String sessionId) {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
		accessor.setSessionId(sessionId);
		Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
		return new SessionDisconnectEvent(this, message, sessionId, null);
	}
}
