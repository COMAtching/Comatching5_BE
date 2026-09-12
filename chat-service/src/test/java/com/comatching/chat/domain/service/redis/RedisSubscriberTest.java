package com.comatching.chat.domain.service.redis;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.domain.service.chat.ChatService;
import com.comatching.chat.domain.service.presence.ChatRoomPresenceTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
class RedisSubscriberTest {

	private static final String ROOM_ID = "room-1";
	private static final Long INITIATOR_ID = 1L;
	private static final Long TARGET_ID = 2L;

	@Mock
	private RedisTemplate<String, Object> redisTemplate;

	@Mock
	private SimpMessageSendingOperations messagingTemplate;

	@Mock
	private ChatRoomRepository chatRoomRepository;

	@Mock
	private BlockService blockService;

	@Mock
	private ChatRoomPresenceTracker presenceTracker;

	@Mock
	private ChatService chatService;

	@Mock
	private RedisPublisher redisPublisher;

	private RedisSubscriber redisSubscriber;

	private final ObjectMapper objectMapper = createObjectMapper();

	@BeforeEach
	void setUp() {
		redisSubscriber = new RedisSubscriber(
			objectMapper,
			redisTemplate,
			messagingTemplate,
			chatRoomRepository,
			blockService,
			presenceTracker,
			chatService,
			redisPublisher
		);
		given(redisTemplate.getStringSerializer()).willReturn(new StringRedisSerializer());
	}

	@Test
	@DisplayName("수신자가 방을 보고 있으면 브로드캐스트 후 서버가 대신 읽음 처리하고 READ를 발행한다")
	void onMessage_autoReadsWhenReceiverIsViewingRoom() throws Exception {
		ChatRoom room = chatRoom();
		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
		given(blockService.isBlocked(TARGET_ID, INITIATOR_ID)).willReturn(false);
		given(presenceTracker.isViewing(ROOM_ID, TARGET_ID)).willReturn(true);

		ChatMessageResponse readResponse = readResponse(TARGET_ID);
		given(chatService.markAsRead(ROOM_ID, TARGET_ID)).willReturn(readResponse);

		redisSubscriber.onMessage(redisMessage(talkResponse(INITIATOR_ID)), null);

		then(messagingTemplate).should().convertAndSend(eq("/topic/chat.room." + ROOM_ID), any(ChatMessageResponse.class));
		then(chatService).should().markAsRead(ROOM_ID, TARGET_ID);
		then(redisPublisher).should().publish(any(ChannelTopic.class), eq(readResponse));
	}

	@Test
	@DisplayName("수신자가 방을 보고 있지 않으면 읽음 처리하지 않는다")
	void onMessage_skipsAutoReadWhenReceiverIsNotViewing() throws Exception {
		ChatRoom room = chatRoom();
		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
		given(blockService.isBlocked(TARGET_ID, INITIATOR_ID)).willReturn(false);
		given(presenceTracker.isViewing(ROOM_ID, TARGET_ID)).willReturn(false);

		redisSubscriber.onMessage(redisMessage(talkResponse(INITIATOR_ID)), null);

		then(messagingTemplate).should().convertAndSend(eq("/topic/chat.room." + ROOM_ID), any(ChatMessageResponse.class));
		then(chatService).shouldHaveNoInteractions();
		then(redisPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("READ 메시지에는 다시 읽음 처리하지 않는다 - 무한 발행을 막는다")
	void onMessage_neverAutoReadsReadMessages() throws Exception {
		ChatRoom room = chatRoom();
		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
		given(blockService.isBlocked(TARGET_ID, INITIATOR_ID)).willReturn(false);

		redisSubscriber.onMessage(redisMessage(readResponse(INITIATOR_ID)), null);

		then(messagingTemplate).should().convertAndSend(eq("/topic/chat.room." + ROOM_ID), any(ChatMessageResponse.class));
		then(chatService).shouldHaveNoInteractions();
		then(redisPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("수신자가 발신자를 차단했으면 전달도 읽음 처리도 하지 않는다")
	void onMessage_skipsEverythingWhenBlocked() throws Exception {
		ChatRoom room = chatRoom();
		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
		given(blockService.isBlocked(TARGET_ID, INITIATOR_ID)).willReturn(true);

		redisSubscriber.onMessage(redisMessage(talkResponse(INITIATOR_ID)), null);

		then(messagingTemplate).shouldHaveNoInteractions();
		then(chatService).shouldHaveNoInteractions();
		then(redisPublisher).shouldHaveNoInteractions();
	}

	private ChatRoom chatRoom() {
		ChatRoom room = ChatRoom.builder()
			.matchingId(100L)
			.initiatorUserId(INITIATOR_ID)
			.targetUserId(TARGET_ID)
			.build();
		ReflectionTestUtils.setField(room, "id", ROOM_ID);
		return room;
	}

	private ChatMessageResponse talkResponse(Long senderId) {
		return new ChatMessageResponse(
			"message-1", ROOM_ID, senderId, "hello", MessageType.TALK,
			LocalDateTime.of(2026, 8, 26, 10, 0), 1
		);
	}

	private ChatMessageResponse readResponse(Long senderId) {
		return new ChatMessageResponse(
			null, ROOM_ID, senderId, null, MessageType.READ,
			LocalDateTime.of(2026, 8, 26, 10, 1), 0
		);
	}

	private DefaultMessage redisMessage(ChatMessageResponse response) throws Exception {
		byte[] body = objectMapper.writeValueAsBytes(response);
		return new DefaultMessage("chatroom".getBytes(StandardCharsets.UTF_8), body);
	}

	private static ObjectMapper createObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return mapper;
	}
}
