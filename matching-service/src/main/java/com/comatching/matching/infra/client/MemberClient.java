package com.comatching.matching.infra.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;

@FeignClient(name = "member-service", path = "/api/internal/members", url = "${member-service.url}")
public interface MemberClient {

	@GetMapping("/profile")
	ProfileResponse getProfile(
		@RequestHeader("X-Member-Id") Long memberId
	);

	@PostMapping("/profiles/bulk")
	List<ProfileResponse> getProfiles(@RequestBody List<Long> memberIds);
}
