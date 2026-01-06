package com.comatching.member.infra.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;
import com.comatching.member.domain.service.MemberService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/internal/members")
@RequiredArgsConstructor
public class MemberInternalController {

	private final MemberService memberService;

	@PostMapping("/social")
	public MemberLoginDto socialLogin(@RequestBody SocialLoginRequestDto request) {
		return memberService.socialLogin(request);
	}

	@GetMapping("/{memberId}")
	public MemberLoginDto getMemberById(@PathVariable Long memberId) {
		return memberService.getMemberById(memberId);
	}

	@GetMapping
	public MemberLoginDto getMemberByEmail(@RequestParam String email) {
		return memberService.getMemberByEmail(email);
	}
}
