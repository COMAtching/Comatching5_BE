package com.comatching.auth.global.security.refresh.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.comatching.auth.infra.controller.AuthController;
import com.comatching.auth.domain.dto.TokenResponse;
import com.comatching.auth.global.config.SecurityConfig;
import com.comatching.auth.global.security.oauth2.handler.CustomOAuth2FailureHandler;
import com.comatching.auth.global.security.oauth2.handler.CustomOAuth2SuccessHandler;
import com.comatching.auth.global.security.oauth2.service.CustomOAuth2UserService;
import com.comatching.auth.global.security.oauth2.service.CustomOidcUserService;
import com.comatching.auth.global.security.refresh.repository.RefreshTokenRepository;
import com.comatching.auth.domain.service.auth.AuthService;
import com.comatching.auth.domain.service.auth.SignupService;
import com.comatching.auth.domain.service.mail.EmailService;
import com.comatching.auth.global.security.service.CustomUserDetailsService;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.service.S3Service;
import com.comatching.common.util.JwtUtil;

import jakarta.servlet.http.Cookie;

@Disabled("통합 테스트 환경에서만 실행 - Spring Security 필터 체인 전체 설정 필요")
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtUtil.class})
@TestPropertySource(locations = "classpath:application.yml")
@DisplayName("토큰 재발급 플로우 테스트")
class AuthReissueFlowTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private SignupService signupService;

	@MockitoBean
	private EmailService emailService;

	@MockitoBean
	private CustomUserDetailsService customUserDetailsService;

	@MockitoBean
	private MemberServiceClient memberServiceClient;

	@MockitoBean
	private RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private S3Service s3Service;

	@MockitoBean
	private CustomOAuth2UserService customOAuth2UserService;

	@MockitoBean
	private CustomOidcUserService customOidcUserService;

	@MockitoBean
	private CustomOAuth2SuccessHandler customOAuth2SuccessHandler;

	@MockitoBean
	private CustomOAuth2FailureHandler customOAuth2FailureHandler;

	@Nested
	@DisplayName("POST /auth/reissue")
	class Reissue {

		@Test
		@DisplayName("유효한 리프레시 토큰으로 재발급하면 새로운 토큰 쿠키와 함께 성공 응답을 반환한다")
		void shouldReturnNewTokensWhenRefreshTokenIsValid() throws Exception {
			// given
			String oldRefreshToken = "old_refresh_token";
			TokenResponse mockResponse = new TokenResponse("new_access", "new_refresh");

			given(authService.reissue(oldRefreshToken)).willReturn(mockResponse);

			// when
			ResultActions result = mockMvc.perform(post("/auth/reissue")
				.cookie(new Cookie("refreshToken", oldRefreshToken)));

			// then
			result.andExpect(status().isOk())
				.andExpect(header().stringValues(
					"Set-Cookie",
					hasItems(
						containsString("accessToken="),
						containsString("refreshToken=")
					)
				))
				.andExpect(jsonPath("$.code").value("GEN-000"))
				.andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."));
		}

		@Test
		@DisplayName("리프레시 토큰 쿠키가 없으면 400 에러를 반환한다")
		void shouldReturn400WhenRefreshTokenCookieIsMissing() throws Exception {
			// when
			ResultActions result = mockMvc.perform(post("/auth/reissue"));

			// then
			result.andExpect(status().isBadRequest());
		}
	}
}