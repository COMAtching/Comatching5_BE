package com.comatching.auth.global.security;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.comatching.auth.domain.dto.LoginRequest;
import com.comatching.auth.global.config.SecurityConfig;
import com.comatching.auth.global.security.refresh.repository.RefreshTokenRepository;
import com.comatching.auth.global.security.service.CustomUserDetailsService;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.service.S3Service;
import com.comatching.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(SecurityConfig.class)
@Import({JwtUtil.class})
public class LoginFlowTest {

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
	private S3Service s3Service;

	@MockitoBean
	private RefreshTokenRepository refreshTokenRepository;

	@Test
	void login_success() throws Exception {
		//given
		String email = "test@comatching.com";
		String password = "password123!";
		LoginRequest loginRequest = new LoginRequest(email, password);

		String encodedPassword = passwordEncoder.encode(password);
		MemberLoginDto mockMember = new MemberLoginDto(1L, email, encodedPassword, "ROLE_USER", "ACTIVE");

		given(customUserDetailsService.loadUserByUsername(email))
			.willReturn(new UserPrincipal(mockMember));

		//when
		ResultActions result = mockMvc.perform(post("/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(loginRequest)));

		//then
		result.andExpect(status().isOk())
			.andExpect(header().exists("Set-Cookie"))
			.andExpect(cookie().exists("accessToken"))
			.andExpect(cookie().exists("refreshToken"))
			.andExpect(cookie().httpOnly("accessToken", true))
			.andExpect(cookie().httpOnly("refreshToken", true))
			.andExpect(cookie().path("refreshToken", "/auth/reissue"))
			.andExpect(jsonPath("$.code").value("GEN-000"))
			.andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."));
	}

	@Test
	void login_Failure() throws Exception {
		// given
		String email = "test@comatching.com";
		LoginRequest loginRequest = new LoginRequest(email, "wrong_password");

		String encodedPassword = passwordEncoder.encode("correct_password");
		MemberLoginDto mockMember = new MemberLoginDto(1L, email, encodedPassword, "ROLE_USER", "ACTIVE");

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