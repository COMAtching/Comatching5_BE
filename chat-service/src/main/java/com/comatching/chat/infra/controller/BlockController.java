package com.comatching.chat.infra.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.chat.domain.dto.BlockRequest;
import com.comatching.chat.domain.dto.BlockStatusResponse;
import com.comatching.chat.domain.dto.BlockedUserResponse;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/blocks")
public class BlockController {

	private final BlockService blockService;

	@PostMapping
	public ResponseEntity<ApiResponse<Void>> blockUser(
		@CurrentMember MemberInfo memberInfo,
		@Valid @RequestBody BlockRequest request
	) {
		blockService.blockUser(memberInfo.memberId(), request.targetUserId());
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@DeleteMapping("/{targetUserId}")
	public ResponseEntity<ApiResponse<Void>> unblockUser(
		@CurrentMember MemberInfo memberInfo,
		@PathVariable Long targetUserId
	) {
		blockService.unblockUser(memberInfo.memberId(), targetUserId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<BlockedUserResponse>>> getBlockedUsers(
		@CurrentMember MemberInfo memberInfo
	) {
		List<BlockedUserResponse> blockedUsers = blockService.getBlockedUsers(memberInfo.memberId());
		return ResponseEntity.ok(ApiResponse.ok(blockedUsers));
	}

	@GetMapping("/{targetUserId}/status")
	public ResponseEntity<ApiResponse<BlockStatusResponse>> getBlockStatus(
		@CurrentMember MemberInfo memberInfo,
		@PathVariable Long targetUserId
	) {
		boolean isBlocked = blockService.isBlocked(memberInfo.memberId(), targetUserId);
		return ResponseEntity.ok(ApiResponse.ok(new BlockStatusResponse(isBlocked)));
	}
}
