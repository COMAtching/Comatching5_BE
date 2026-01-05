package com.comatching.auth.global.security.refresh.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.comatching.auth.domain.dto.TokenResponse;
import com.comatching.auth.global.config.SecurityConfig;
import com.comatching.auth.global.security.refresh.repository.RefreshTokenRepository;
import com.comatching.auth.global.security.refresh.service.RefreshTokenService;
import com.comatching.auth.global.security.service.CustomUserDetailsService;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.service.S3Service;
import com.comatching.common.util.JwtUtil;

import jakarta.servlet.http.Cookie;

@WebMvcTest(RefreshTokenController.class)
@Import({SecurityConfig.class, JwtUtil.class})
class AuthReissueFlowTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RefreshTokenService refreshTokenService;

	@MockitoBean
	private CustomUserDetailsService customUserDetailsService;

	@MockitoBean
	private MemberServiceClient memberServiceClient;

	@MockitoBean
	private RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private S3Service s3Service;

	@Test
	void reissue_flow_success() throws Exception {
		//given
		String oldRefreshToken = "old_refresh_token";
		TokenResponse mockResponse = new TokenResponse("new_access", "new_refresh");

		given(refreshTokenService.reissue(oldRefreshToken)).willReturn(mockResponse);

		//when
		ResultActions result = mockMvc.perform(post("/auth/reissue")
			.cookie(new Cookie("refreshToken", oldRefreshToken)));

		//then
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
	void reissue_flow_no_cookie() throws Exception {
		// when
		ResultActions result = mockMvc.perform(post("/auth/reissue"));

		// then
		result.andExpect(status().isBadRequest());
	}
}