package com.comatching.member.infra.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.member.domain.dto.ProfileUpdateRequest;
import com.comatching.member.domain.service.profile.ProfileCreateService;
import com.comatching.member.domain.service.profile.ProfileManageService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProfileController {

	private final ProfileCreateService profileCreateService;
	private final ProfileManageService profileManageService;

	@PostMapping("/internal/members/profile")
	public ProfileResponse createProfile(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestBody ProfileCreateRequest request
	) {
		return profileCreateService.createProfile(memberId, request);
	}

	@GetMapping("/internal/members/profile")
	public ProfileResponse getProfile(@RequestHeader("X-Member-Id") Long memberId) {
		return profileManageService.getProfile(memberId);
	}

	@RequireRole(MemberRole.ROLE_USER)
	@GetMapping("/members/profile")
	public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(@CurrentMember MemberInfo memberInfo) {
		ProfileResponse response = profileManageService.getProfile(memberInfo.memberId());

		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@RequireRole(MemberRole.ROLE_USER)
	@PatchMapping("/members/profile")
	public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody ProfileUpdateRequest request
	) {
		ProfileResponse response = profileManageService.updateProfile(memberInfo.memberId(), request);

		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
