package com.comatching.chat.infra.controller;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.chat.domain.dto.ChatMessageRequest;
import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.comatching.chat.domain.service.chat.ChatService;
import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

	private final SimpMessagingTemplate messagingTemplate;
	private final ChatService chatService;

	@MessageMapping("/chat/message")
	public void sendMessage(@Payload ChatMessageRequest request, SimpMessageHeaderAccessor headerAccessor) {
		log.info("STOMP Message Received: {}", request);

		Long memberId = (Long)headerAccessor.getSessionAttributes().get("memberId");

		if (memberId == null) {
			log.error("인증되지 않은 사용자의 접근입니다.");
			return;
		}

		ChatMessageRequest securedRequest = new ChatMessageRequest(
			request.roomId(),
			memberId,
			request.content(),
			request.type()
		);

		log.info("Secure Message Received from User: {}", memberId);

		ChatMessageResponse response = chatService.processMessage(securedRequest);

		// 경로: /topic/chat.room.{roomId}
		// 이 경로를 구독(Subscribe)하고 있는 모든 사용자에게 메시지가 전달됩니다.
		messagingTemplate.convertAndSend("/topic/chat.room." + request.roomId(), response);
	}

	@GetMapping("/api/chat/rooms/{roomId}/messages")
	public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getChatMessages(
		@PathVariable String roomId,
		@CurrentMember MemberInfo memberInfo,
		@PageableDefault(size = 20) Pageable pageable) {

		List<ChatMessageResponse> messages = chatService.getChatHistory(roomId, memberInfo.memberId(), pageable);
		return ResponseEntity.ok(ApiResponse.ok(messages));
	}
}
