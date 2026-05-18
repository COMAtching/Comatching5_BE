package com.comatching.chat.infra.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.chat.domain.dto.ChatMemberProfileResponse;
import com.comatching.chat.domain.service.profile.ChatProfileService;
import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/members")
public class ChatProfileController {

	private final ChatProfileService chatProfileService;

	@GetMapping("/{memberId}/profile")
	public ResponseEntity<ApiResponse<ChatMemberProfileResponse>> getMemberProfile(
		@CurrentMember MemberInfo memberInfo,
		@PathVariable Long memberId
	) {
		ChatMemberProfileResponse response = chatProfileService.getMemberProfile(memberInfo.memberId(), memberId);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
