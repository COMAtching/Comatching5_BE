package com.comatching.user.global.security.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.comatching.user.global.security.UserPrincipal;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.common.dto.auth.MemberLoginDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

	private final MemberService memberService;

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

		Member member;

		try {
			member = memberService.getMemberByEmail(email);
		} catch (Exception e) {
			throw new UsernameNotFoundException("사용자 정보를 불러오는 중 서버 통신 에러가 발생했습니다.");
		}

		if (member == null) {
			throw new UsernameNotFoundException("존재하지 않는 회원입니다.");
		}

		MemberLoginDto memberDto = MemberLoginDto.builder()
			.id(member.getId())
			.email(member.getEmail())
			.password(member.getPassword())
			.role(member.getRole().name())
			.status(member.getStatus().name())
			.socialType(member.getSocialType())
			.socialId(member.getSocialId())
			.nickname(member.getProfile() != null ? member.getProfile().getNickname() : null)
			.build();

		return new UserPrincipal(memberDto);
	}
}
