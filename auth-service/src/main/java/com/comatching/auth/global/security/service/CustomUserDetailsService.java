package com.comatching.auth.global.security.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.comatching.auth.global.security.UserPrincipal;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.dto.auth.MemberLoginDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

	private final MemberServiceClient memberServiceClient;

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

		MemberLoginDto memberDto;

		try {
			memberDto = memberServiceClient.getMemberByEmail(email);
		} catch (Exception e) {
			throw new UsernameNotFoundException("사용자 정보를 불러오는 중 서버 통신 에러가 발생했습니다.");
		}

		if (memberDto == null) {
			throw new UsernameNotFoundException("존재하지 않는 회원입니다.");
		}

		return new UserPrincipal(memberDto);
	}
}
