package com.comatching.user.infra.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.user.domain.auth.dto.TokenResponse;
import com.comatching.user.domain.auth.service.AuthService;
import com.comatching.user.domain.auth.service.SignupService;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.domain.member.service.ProfileManageService;
import com.comatching.user.global.security.cookie.AuthCookieFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AuthService authService;

	@Mock
	private SignupService signupService;

	@Mock
	private EmailService emailService;

	@Mock
	private ProfileManageService profileManageService;

	@Mock
	private MemberService memberService;

	@BeforeEach
	void setUp() {
		AuthCookieFactory authCookieFactory = new AuthCookieFactory(true, "comatching.site", "Lax");
		AuthController authController = new AuthController(
			authService,
			signupService,
			emailService,
			profileManageService,
			memberService,
			authCookieFactory
		);

		mockMvc = MockMvcBuilders.standaloneSetup(authController)
			.setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
			.build();
	}

	@Test
	@DisplayName("POST /api/auth/reissue uses refresh token cookie and resets token cookies")
	void reissue_success() throws Exception {
		// given
		given(authService.reissue("old-refresh-token"))
			.willReturn(new TokenResponse("new-access-token", "new-refresh-token"));

		// when
		MvcResult result = mockMvc.perform(post("/api/auth/reissue")
				.cookie(new Cookie("refreshToken", "old-refresh-token")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("GEN-000"))
			.andReturn();

		// then
		List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		assertThat(setCookies).hasSize(2);
		assertThat(setCookies).anySatisfy(cookie -> assertThat(cookie)
			.contains("accessToken=new-access-token")
			.contains("Path=/")
			.contains("Domain=comatching.site")
			.contains("Secure")
			.contains("HttpOnly")
			.contains("SameSite=Lax"));
		assertThat(setCookies).anySatisfy(cookie -> assertThat(cookie)
			.contains("refreshToken=new-refresh-token")
			.contains("Path=/api/auth")
			.contains("Domain=comatching.site")
			.contains("Secure")
			.contains("HttpOnly")
			.contains("SameSite=Lax"));
		then(authService).should().reissue("old-refresh-token");
	}

	@Test
	@DisplayName("POST /api/auth/reissue returns 401 when refresh token cookie is missing")
	void reissue_missingRefreshTokenCookie() throws Exception {
		mockMvc.perform(post("/api/auth/reissue"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(GeneralErrorCode.UNAUTHORIZED.getCode()));

		then(authService).shouldHaveNoInteractions();
	}
}
