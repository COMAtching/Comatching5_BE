package com.comatching.auth.global.security.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
@DisplayName("CustomUserDetailsService 테스트")
class CustomUserDetailsServiceTest {

	@Mock
	private MemberServiceClient memberServiceClient;

	@InjectMocks
	private CustomUserDetailsService customUserDetailsService;

	private MemberLoginDto createMemberLoginDto(Long id, String email, String password) {
		return MemberLoginDto.builder()
			.id(id)
			.email(email)
			.password(password)
			.role("ROLE_USER")
			.status("ACTIVE")
			.nickname("테스트유저")
			.build();
	}

	@Nested
	@DisplayName("loadUserByUsername 메서드")
	class LoadUserByUsername {

		@Test
		@DisplayName("존재하는 이메일로 조회하면 UserDetails를 반환한다")
		void shouldReturnUserDetailsWhenEmailExists() {
			// given
			String email = "test@comatching.com";
			MemberLoginDto mockDto = createMemberLoginDto(1L, email, "password123!");
			given(memberServiceClient.getMemberByEmail(email)).willReturn(mockDto);

			// when
			UserDetails userDetails = customUserDetailsService.loadUserByUsername(email);

			// then
			assertThat(userDetails.getUsername()).isEqualTo(email);
			assertThat(((UserPrincipal) userDetails).getId()).isEqualTo(1L);
		}

		@Test
		@DisplayName("존재하지 않는 이메일로 조회하면 UsernameNotFoundException을 던진다")
		void shouldThrowExceptionWhenEmailNotFound() {
			// given
			String email = "none@comatching.com";
			given(memberServiceClient.getMemberByEmail(email)).willReturn(null);

			// when & then
			assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername(email))
				.isInstanceOf(UsernameNotFoundException.class)
				.hasMessageContaining("존재하지 않는 회원입니다.");
		}
	}
}