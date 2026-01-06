package com.comatching.auth.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;

@FeignClient(name = "member-service", path = "/api/internal/members", url = "${member-service.url}")
public interface MemberServiceClient {

	@GetMapping
	MemberLoginDto getMemberByEmail(@RequestParam("email") String email);

	@GetMapping("/{memberId}")
	MemberLoginDto getMemberById(@PathVariable("memberId") Long id);

	@PostMapping("/social")
	MemberLoginDto socialLogin(@RequestBody SocialLoginRequestDto request);
}
