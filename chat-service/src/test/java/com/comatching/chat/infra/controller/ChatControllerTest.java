package com.comatching.chat.infra.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import com.comatching.chat.domain.dto.ChatMessageRequest;
import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.service.chat.ChatService;
import com.comatching.chat.domain.service.chatroom.ChatRoomService;
import com.comatching.chat.domain.service.redis.RedisPublisher;
import com.comatching.common.service.S3Service;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

	private static final String ROOM_ID = "room-1";
	private static final Long MEMBER_ID = 1L;

	@Mock
	private RedisPublisher redisPublisher;

	@Mock
	private ChatService chatService;

	@Mock
	private ChatRoomService chatRoomService;

	@Mock
	private S3Service s3Service;

	@InjectMocks
	private ChatController chatController;

	@Test
	@DisplayName("채팅 메시지 처리 후 Redis publish를 수행한다")
	void sendMessage_publishesAfterProcessingMessage() {
		// given
		ChatMessageRequest request = new ChatMessageRequest(ROOM_ID, null, null, "hello", MessageType.TALK);
		ChatMessageRequest securedRequest = new ChatMessageRequest(ROOM_ID, MEMBER_ID, "sender", "hello", MessageType.TALK);
		ChatMessageResponse response = new ChatMessageResponse(
			"message-1",
			ROOM_ID,
			MEMBER_ID,
			"hello",
			MessageType.TALK,
			LocalDateTime.of(2026, 5, 18, 0, 0),
			1
		);
		SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
		headerAccessor.setSessionAttributes(new HashMap<>(Map.of(
			"memberId", MEMBER_ID,
			"nickname", "sender"
		)));

		given(chatService.processMessage(securedRequest)).willReturn(response);
		given(redisPublisher.publish(any(ChannelTopic.class), eq(response))).willReturn(1L);

		// when
		chatController.sendMessage(request, headerAccessor);

		// then
		InOrder inOrder = inOrder(chatService, redisPublisher);
		inOrder.verify(chatService).processMessage(securedRequest);
		inOrder.verify(redisPublisher).publish(any(ChannelTopic.class), eq(response));
	}
}
