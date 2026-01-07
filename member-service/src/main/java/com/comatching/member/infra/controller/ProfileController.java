package com.comatching.member.infra.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.member.domain.service.ProfileService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/internal/members/profile")
@RequiredArgsConstructor
public class ProfileController {

	private final ProfileService profileService;

	@PostMapping
	public ProfileResponse createProfile(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestBody ProfileCreateRequest request
	) {
		return profileService.createProfile(memberId, request);
	}
}
