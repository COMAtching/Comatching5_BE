package com.comatching.member.infra.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.member.domain.service.ProfileService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/internal/members/profile")
@RequiredArgsConstructor
public class ProfileController {

	private final ProfileService profileService;

	@PostMapping
	public ResponseEntity<ApiResponse<ProfileResponse>> createProfile(
		@RequestHeader("X-Member-Id") Long memberId, // Gateway에서 헤더로 넣어준 ID 사용
		@RequestBody ProfileCreateRequest request
	) {
		ProfileResponse profile = profileService.createProfile(memberId, request);
		return ResponseEntity.ok(ApiResponse.ok(profile));
	}
}
