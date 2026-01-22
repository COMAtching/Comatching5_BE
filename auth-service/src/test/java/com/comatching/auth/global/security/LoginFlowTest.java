package com.comatching.auth.global.security;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.comatching.auth.domain.dto.LoginRequest;
import com.comatching.auth.domain.service.auth.AuthService;
import com.comatching.auth.domain.service.auth.SignupService;
import com.comatching.auth.domain.service.mail.EmailService;
import com.comatching.auth.global.config.SecurityConfig;
import com.comatching.auth.global.security.oauth2.handler.CustomOAuth2FailureHandler;
import com.comatching.auth.global.security.oauth2.handler.CustomOAuth2SuccessHandler;
import com.comatching.auth.global.security.oauth2.service.CustomOAuth2UserService;
import com.comatching.auth.global.security.oauth2.service.CustomOidcUserService;
import com.comatching.auth.global.security.refresh.repository.RefreshTokenRepository;
import com.comatching.auth.global.security.service.CustomUserDetailsService;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.service.S3Service;
import com.comatching.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

@Disabled("통합 테스트 환경에서만 실행 - Spring Security 필터 체인 전체 설정 필요")
@WebMvcTest(SecurityConfig.class)
@Import({JwtUtil.class})
@TestPropertySource(locations = "classpath:application.yml")
@DisplayName("로그인 플로우 테스트")
class LoginFlowTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@MockitoBean
	private CustomUserDetailsService customUserDetailsService;

	@MockitoBean
	private MemberServiceClient memberServiceClient;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private SignupService signupService;

	@MockitoBean
	private EmailService emailService;

	@MockitoBean
	private S3Service s3Service;

	@MockitoBean
	private RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private CustomOAuth2UserService customOAuth2UserService;

	@MockitoBean
	private CustomOidcUserService customOidcUserService;

	@MockitoBean
	private CustomOAuth2SuccessHandler customOAuth2SuccessHandler;

	@MockitoBean
	private CustomOAuth2FailureHandler customOAuth2FailureHandler;

	private MemberLoginDto createMemberLoginDto(Long id, String email, String encodedPassword) {
		return MemberLoginDto.builder()
			.id(id)
			.email(email)
			.password(encodedPassword)
			.role("ROLE_USER")
			.status("ACTIVE")
			.nickname("테스트유저")
			.build();
	}

	@Nested
	@DisplayName("POST /auth/login")
	class Login {

		@Test
		@DisplayName("올바른 이메일과 비밀번호로 로그인하면 쿠키와 함께 성공 응답을 반환한다")
		void shouldReturnSuccessWithCookiesWhenCredentialsAreValid() throws Exception {
			// given
			String email = "test@comatching.com";
			String password = "password123!";
			LoginRequest loginRequest = new LoginRequest(email, password);

			String encodedPassword = passwordEncoder.encode(password);
			MemberLoginDto mockMember = createMemberLoginDto(1L, email, encodedPassword);

			given(customUserDetailsService.loadUserByUsername(email))
				.willReturn(new UserPrincipal(mockMember));

			// when
			ResultActions result = mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(loginRequest)));

			// then
			result.andExpect(status().isOk())
				.andExpect(header().exists("Set-Cookie"))
				.andExpect(cookie().exists("accessToken"))
				.andExpect(cookie().exists("refreshToken"))
				.andExpect(cookie().httpOnly("accessToken", true))
				.andExpect(cookie().httpOnly("refreshToken", true))
				.andExpect(cookie().path("refreshToken", "/api/auth"))
				.andExpect(jsonPath("$.code").value("GEN-000"))
				.andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."));
		}

		@Test
		@DisplayName("잘못된 비밀번호로 로그인하면 401 에러를 반환한다")
		void shouldReturn401WhenPasswordIsWrong() throws Exception {
			// given
			String email = "test@comatching.com";
			LoginRequest loginRequest = new LoginRequest(email, "wrong_password");

			String encodedPassword = passwordEncoder.encode("correct_password");
			MemberLoginDto mockMember = createMemberLoginDto(1L, email, encodedPassword);

			given(customUserDetailsService.loadUserByUsername(email))
				.willReturn(new UserPrincipal(mockMember));

			// when
			ResultActions result = mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(loginRequest)));

			// then
			result.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("GEN-010"))
				.andExpect(jsonPath("$.message").value("인증되지 않은 사용자입니다."))
				.andExpect(jsonPath("$.data").value("이메일 또는 비밀번호가 올바르지 않습니다."));
		}
	}
}