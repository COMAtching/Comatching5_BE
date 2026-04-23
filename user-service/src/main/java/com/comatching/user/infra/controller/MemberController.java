package com.comatching.user.infra.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.RealNameUpdateRequestDto;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.user.domain.member.service.MemberService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@RequireRole(MemberRole.ROLE_USER)
	@PatchMapping("/real-name")
	public ResponseEntity<ApiResponse<Void>> updateRealName(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody RealNameUpdateRequestDto request
	) {
		memberService.updateRealName(memberInfo.memberId(), request.realName());
		return ResponseEntity.ok(ApiResponse.ok());
	}
}
