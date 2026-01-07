package com.comatching.auth.domain.service.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.comatching.auth.domain.service.mail.EmailService;
import com.comatching.auth.global.exception.AuthErrorCode;
import com.comatching.auth.global.security.refresh.RefreshToken;
import com.comatching.auth.global.security.refresh.repository.RefreshTokenRepository;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.util.JwtUtil;

import jakarta.servlet.http.HttpServletResponse;

@ExtendWith(MockitoExtension.class)
class SignupServiceImplTest {

	@Mock
	private EmailService emailService;

	@Mock
	private MemberServiceClient memberServiceClient;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private JwtUtil jwtUtil;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private HttpServletResponse response;

	@InjectMocks
	private SignupServiceImpl signupService;

	@Test
	void signup_success() {
		// given
		SignupRequest request = new SignupRequest("test@test.com", "password1234");

		given(emailService.isVerified(request.email())).willReturn(true);
		given(passwordEncoder.encode(request.password())).willReturn("encodedPassword");

		// when
		signupService.signup(request);

		// then
		// MemberServiceClient가 GUEST 권한으로 호출되었는지 검증
		verify(memberServiceClient, times(1)).createMember(argThat(dto ->
			dto.email().equals(request.email()) &&
				dto.password().equals("encodedPassword") &&
				dto.role() == MemberRole.ROLE_GUEST
		));
	}

	@Test
	void signup_fail_email_not_verified() {
		// given
		SignupRequest request = new SignupRequest("notverified@test.com", "password1234");

		given(emailService.isVerified(request.email())).willReturn(false);

		// when & then
		assertThatThrownBy(() -> signupService.signup(request))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(AuthErrorCode.EMAIL_NOT_AUTHENTICATED);

		verify(memberServiceClient, never()).createMember(any());
	}

	@Test
	void completeSignup_success() {
		// given
		Long memberId = 1L;
		ProfileCreateRequest request = new ProfileCreateRequest(
			"nickname", Gender.MALE, LocalDate.of(2026, 1, 6), "mbti", "intro", "imgUrl", null, null
		);

		ProfileResponse mockProfileResponse = new ProfileResponse(
			memberId, "test@test.com", "nickname", Gender.MALE, LocalDate.of(2026, 1, 6), "mbti", "intro", "imgUrl",
			null, null
		);

		MemberInfo memberInfo = new MemberInfo(memberId, "test@test.com", "ROLE_GUEST");

		given(memberServiceClient.createProfile(memberId, request)).willReturn(mockProfileResponse);

		String accessToken = "mock-access-token";
		String refreshToken = "mock-refresh-token";
		given(jwtUtil.createAccessToken(anyLong(), anyString(), anyString(), anyString())).willReturn(accessToken);
		given(jwtUtil.createRefreshToken(anyLong())).willReturn(refreshToken);

		// when
		ProfileResponse result = signupService.completeSignup(memberInfo, request, response);

		// then
		assertThat(result.memberId()).isEqualTo(memberId);
		assertThat(result.email()).isEqualTo("test@test.com");

		verify(refreshTokenRepository, times(1)).save(any(RefreshToken.class));

		verify(response, times(2)).addHeader(eq("Set-Cookie"), anyString());
	}
}