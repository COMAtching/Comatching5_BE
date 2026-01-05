package com.comatching.auth.global.security.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.comatching.auth.global.security.UserPrincipal;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.dto.auth.MemberLoginDto;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

	@Mock
	private MemberServiceClient memberServiceClient;

	@InjectMocks
	private CustomUserDetailsService customUserDetailsService;

	@Test
	void loadUserByUsername_Success() {
		// given
		String email = "test@comatching.com";
		MemberLoginDto mockDto = new MemberLoginDto(1L, email, "password123!", "ROLE_USER", "ACTIVE");
		given(memberServiceClient.getMemberByEmail(email)).willReturn(mockDto);

		// when
		UserDetails userDetails = customUserDetailsService.loadUserByUsername(email);

		// then
		assertThat(userDetails.getUsername()).isEqualTo(email);
		assertThat(((UserPrincipal) userDetails).getId()).isEqualTo(1L);
	}

	@Test
	void loadUserByUsername_UserNotFound() {
		// given
		String email = "none@comatching.com";
		given(memberServiceClient.getMemberByEmail(email)).willReturn(null);

		// when & then
		assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername(email))
			.isInstanceOf(UsernameNotFoundException.class)
			.hasMessageContaining("존재하지 않는 회원입니다.");
	}

}