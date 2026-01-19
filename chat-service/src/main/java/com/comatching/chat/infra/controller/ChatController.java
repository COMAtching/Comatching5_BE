package com.comatching.chat.infra.controller;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.chat.domain.dto.ChatMessageRequest;
import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.comatching.chat.domain.dto.FileUploadRequest;
import com.comatching.chat.domain.service.chat.ChatService;
import com.comatching.chat.domain.service.redis.RedisPublisher;
import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.dto.s3.S3UploadResponseDto;
import com.comatching.common.service.S3Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

	private final RedisPublisher redisPublisher;
	private final ChatService chatService;
	private final S3Service s3Service;

	private final ChannelTopic topic = new ChannelTopic("chatroom");

	@PostMapping("/api/chat/rooms/{roomId}/files/presigned-url")
	public ResponseEntity<ApiResponse<S3UploadResponseDto>> getPresignedUrl(
		@PathVariable String roomId,
		@RequestBody FileUploadRequest request
	) {
		S3UploadResponseDto response = s3Service.getPresignedPutUrlForChat(roomId, request.filename());
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@MessageMapping("/chat/message")
	public void sendMessage(
		@Payload ChatMessageRequest request,
		SimpMessageHeaderAccessor headerAccessor) {

		Long memberId = (Long)headerAccessor.getSessionAttributes().get("memberId");
		String nickname = headerAccessor.getSessionAttributes().get("nickname").toString();

		if (memberId == null) {
			log.error("인증되지 않은 사용자의 접근입니다.");
			return;
		}

		ChatMessageRequest securedRequest = new ChatMessageRequest(
			request.roomId(),
			memberId,
			nickname,
			request.content(),
			request.type()
		);

		ChatMessageResponse response = chatService.processMessage(securedRequest);

		redisPublisher.publish(topic, response);
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
