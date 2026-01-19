package com.comatching.chat.infra.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.chat.domain.dto.ChatRoomResponse;
import com.comatching.chat.domain.service.chatroom.ChatRoomService;
import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/rooms")
public class ChatRoomController {

	private final ChatRoomService chatRoomService;

	@GetMapping
	public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> getMyChatRooms(@CurrentMember MemberInfo memberInfo) {

		List<ChatRoomResponse> rooms = chatRoomService.getMyChatRooms(memberInfo.memberId());
		return ResponseEntity.ok(ApiResponse.ok(rooms));
	}

	@GetMapping("/unread-count")
	public ResponseEntity<ApiResponse<Long>> getTotalUnreadCount(@CurrentMember MemberInfo memberInfo) {

		long count = chatRoomService.getTotalUnreadCount(memberInfo.memberId());
		return ResponseEntity.ok(ApiResponse.ok(count));
	}
}
