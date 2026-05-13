package com.comatching.user.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.util.JwtUtil;
import com.comatching.user.domain.auth.dto.CompleteSignupResponse;
import com.comatching.user.domain.auth.entity.RefreshToken;
import com.comatching.user.domain.auth.repository.RefreshTokenRepository;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.domain.member.service.ProfileCreateService;
import com.comatching.user.global.security.cookie.AuthCookieFactory;

import io.jsonwebtoken.Claims;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignupServiceImpl 테스트")
class SignupServiceImplTest {

	@Mock
	private EmailService emailService;

	@Mock
	private MemberService memberService;

	@Mock
	private ProfileCreateService profileService;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Test
	@DisplayName("온보딩 완료 후 ROLE_USER 토큰 쿠키와 완료 상태를 반환한다")
	void shouldReturnUserTokenCookieAfterCompleteSignup() {
		// given
		JwtUtil jwtUtil = new JwtUtil(base64Secret(), 86_400_000L, 604_800_000L);
		AuthCookieFactory authCookieFactory = new AuthCookieFactory(true, "comatching.site", "Lax");
		SignupServiceImpl signupService = new SignupServiceImpl(
			emailService,
			memberService,
			profileService,
			passwordEncoder,
			jwtUtil,
			refreshTokenRepository,
			authCookieFactory
		);

		ProfileResponse profileResponse = ProfileResponse.builder()
			.memberId(810L)
			.email("guest@test.com")
			.nickname("온보딩완료")
			.gender(Gender.MALE)
			.birthDate(LocalDate.of(2000, 1, 1))
			.mbti("INTJ")
			.university("가톨릭대학교")
			.major("컴퓨터공학부")
			.contactFrequency("FREQUENT")
			.hobbies(List.of())
			.tags(List.of())
			.build();
		ProfileCreateRequest request = ProfileCreateRequest.builder().build();
		MockHttpServletResponse response = new MockHttpServletResponse();

		given(profileService.createProfile(810L, request)).willReturn(profileResponse);

		// when
		CompleteSignupResponse result = signupService.completeSignup(
			new MemberInfo(810L, "guest@test.com", "ROLE_GUEST"),
			request,
			response
		);

		// then
		assertThat(result.isOnboardingFinished()).isTrue();

		String accessToken = extractCookieValue(response, "accessToken");
		Claims claims = jwtUtil.parseToken(accessToken);
		assertThat(claims.getSubject()).isEqualTo("810");
		assertThat(claims.get("role", String.class)).isEqualTo("ROLE_USER");
		assertThat(claims.get("status", String.class)).isEqualTo("ACTIVE");
		assertThat(claims.get("nickname", String.class)).isEqualTo("온보딩완료");

		assertThat(extractCookieValue(response, "refreshToken")).isNotBlank();

		ArgumentCaptor<RefreshToken> refreshTokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(refreshTokenCaptor.capture());
		assertThat(refreshTokenCaptor.getValue().getMemberId()).isEqualTo(810L);
	}

	private static String base64Secret() {
		return Base64.getEncoder()
			.encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
	}

	private static String extractCookieValue(MockHttpServletResponse response, String cookieName) {
		return response.getHeaders("Set-Cookie")
			.stream()
			.filter(cookie -> cookie.startsWith(cookieName + "="))
			.findFirst()
			.map(cookie -> cookie.substring((cookieName + "=").length(), cookie.indexOf(';')))
			.orElseThrow();
	}
}
