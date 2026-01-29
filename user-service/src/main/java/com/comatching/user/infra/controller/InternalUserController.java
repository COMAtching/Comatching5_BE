package com.comatching.user.infra.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SocialLoginRequestDto;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.service.MemberService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

	private final MemberService memberService;

	@PostMapping("/social")
	public MemberLoginDto socialLogin(@RequestBody SocialLoginRequestDto request) {
		return memberService.socialLogin(request);
	}

	@GetMapping("/{memberId}")
	public MemberLoginDto getMemberById(@PathVariable Long memberId) {
		Member member = memberService.getMemberById(memberId);
		return toLoginDto(member);
	}

	@GetMapping
	public MemberLoginDto getMemberByEmail(@RequestParam String email) {
		Member member = memberService.getMemberByEmail(email);
		return toLoginDto(member);
	}

	@PatchMapping("/password")
	public void updatePassword(@RequestParam String email, @RequestParam String encryptedPassword) {
		memberService.updatePassword(email, encryptedPassword);
	}

	@DeleteMapping("/{memberId}")
	public void withdrawMember(@PathVariable Long memberId) {
		memberService.withdrawMember(memberId);
	}

	private MemberLoginDto toLoginDto(Member member) {
		return MemberLoginDto.builder()
			.id(member.getId())
			.email(member.getEmail())
			.password(member.getPassword())
			.role(member.getRole().name())
			.status(member.getStatus().name())
			.socialType(member.getSocialType())
			.socialId(member.getSocialId())
			.nickname(member.getProfile() != null ? member.getProfile().getNickname() : null)
			.build();
	}
}
