package com.comatching.user.infra.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.mockito.Mockito.doThrow;
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
import com.comatching.user.domain.mail.service.VerificationMailLimitException;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.domain.member.service.ProfileManageService;
import com.comatching.user.global.security.cookie.AuthCookieFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

	private MockMvc mockMvc;
	@Mock private com.comatching.common.util.JwtUtil jwt;

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
			new MailRequestSource(""),
			profileManageService,
			memberService,
			authCookieFactory,
			jwt
		);

		mockMvc = MockMvcBuilders.standaloneSetup(authController)
			.setCustomArgumentResolvers(new com.comatching.common.resolver.MemberInfoArgumentResolver())
			.setControllerAdvice(new VerificationMailAdvice(), new GlobalExceptionHandler(new ObjectMapper()))
			.build();
	}

	@Test
	void logoutResponsesCannotExpireAnotherLoginAndLegacyClientsStillClearLiveSessions() throws Exception {
		var access = new Cookie("accessToken", "access");
		var refresh = new Cookie("refreshToken", "refresh");
		given(authService.logout("access", "refresh", "marker")).willReturn(true);
		mockMvc.perform(post("/api/auth/logout").cookie(access, refresh).header("X-Expected-Session", "marker"))
			.andExpect(status().isOk()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		given(authService.logout("access", "refresh", null)).willReturn(false, true);
		mockMvc.perform(post("/api/auth/logout").cookie(access, refresh))
			.andExpect(status().isOk()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		var live = mockMvc.perform(post("/api/auth/logout").cookie(access, refresh)).andExpect(status().isOk())
			.andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		assertThat(live).hasSize(2).allMatch(cookie -> cookie.contains("Max-Age=0"));
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/auth/withdraw")
				.header("X-Member-Id", "2").header("X-Member-Role", "ROLE_USER").header("X-Expected-Session", "marker"))
			.andExpect(status().isOk()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		then(authService).should().withdraw(2L);
	}

	@Test
	void passwordCodeRouteReturnsTrustedSourceLimitAndRetryAfter() throws Exception {
		doThrow(new VerificationMailLimitException(27))
			.when(emailService).sendPasswordResetCode("fixture@example.invalid", "127.0.0.1");
		mockMvc.perform(post("/api/auth/password/code")
				.header("X-Forwarded-For", "198.51.100.77")
				.contentType("application/json").content("{\"email\":\"fixture@example.invalid\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "27"))
			.andExpect(jsonPath("$.data.retryAfterSeconds").value(27));
	}

	@Test
	void staleOnboardingDraftCannotBeSubmittedWithAnotherLogin() throws Exception {
		var a = new com.comatching.common.auth.LoginSession(1, 0, java.util.UUID.randomUUID());
		var b = new com.comatching.common.auth.LoginSession(2, 0, java.util.UUID.randomUUID());
		given(jwt.readAccess("first")).willReturn(new com.comatching.common.util.JwtUtil.VerifiedToken(a, null));
		given(jwt.readAccess("second")).willReturn(new com.comatching.common.util.JwtUtil.VerifiedToken(b, null));
		String marker = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.get("/api/auth/session").cookie(new Cookie("accessToken", "first")))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		mockMvc.perform(post("/api/auth/signup/profile").header("X-Member-Id", "2")
				.header("X-Member-Role", "ROLE_GUEST").header("X-Onboarding-Session", marker)
				.cookie(new Cookie("accessToken", "second")).contentType("application/json").content("{}"))
			.andExpect(status().isForbidden());
		then(signupService).shouldHaveNoInteractions();
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
	void aPreviousTabCannotRotateAnotherAccountsRefreshCookie() throws Exception {
		var current = new com.comatching.common.auth.LoginSession(2, 0, java.util.UUID.randomUUID());
		given(jwt.readRefresh("current")).willReturn(new com.comatching.common.util.JwtUtil.VerifiedToken(current, null));
		mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", "current"))
				.header("X-Expected-Session", "previous-account-marker"))
			.andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		then(authService).shouldHaveNoInteractions();
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
