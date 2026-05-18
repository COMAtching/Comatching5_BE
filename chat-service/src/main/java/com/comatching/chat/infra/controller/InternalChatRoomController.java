package com.comatching.chat.infra.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.chat.domain.service.chatroom.ChatRoomService;
import com.comatching.common.dto.chat.ChatRoomReferenceResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/chat/rooms")
public class InternalChatRoomController {

	private final ChatRoomService chatRoomService;

	@PostMapping("/references")
	public List<ChatRoomReferenceResponse> getChatRoomReferences(@RequestBody List<Long> matchingIds) {
		return chatRoomService.getChatRoomReferencesByMatchingIds(matchingIds);
	}
}
