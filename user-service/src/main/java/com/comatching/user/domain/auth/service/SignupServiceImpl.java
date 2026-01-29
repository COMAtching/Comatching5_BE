package com.comatching.user.domain.auth.service;

import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.user.domain.auth.dto.CompleteSignupResponse;
import com.comatching.user.domain.auth.entity.RefreshToken;
import com.comatching.user.domain.auth.repository.RefreshTokenRepository;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.domain.member.service.ProfileCreateService;
import com.comatching.user.global.exception.UserErrorCode;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.util.CookieUtil;
import com.comatching.common.util.JwtUtil;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignupServiceImpl implements SignupService {

	private final EmailService emailService;
	private final MemberService memberService;
	private final ProfileCreateService profileService;
	private final PasswordEncoder passwordEncoder;
	private final JwtUtil jwtUtil;
	private final RefreshTokenRepository refreshTokenRepository;

	@Override
	public void signup(SignupRequest request) {

		// 이메일 인증 완료 여부 확인
		if (!emailService.isVerified(request.email())) {
			throw new BusinessException(UserErrorCode.EMAIL_NOT_AUTHENTICATED, "이메일 인증이 완료되지 않았습니다.");
		}

		String encodedPassword = passwordEncoder.encode(request.password());

		memberService.createMember(request.email(), encodedPassword);
	}

	@Override
	@Transactional
	public CompleteSignupResponse completeSignup(MemberInfo memberInfo, ProfileCreateRequest request,
		HttpServletResponse response) {

		// 프로필 생성
		ProfileResponse profileResponse = profileService.createProfile(memberInfo.memberId(), request);

		Long memberId = profileResponse.memberId();
		String email = profileResponse.email();
		String newRole = MemberRole.ROLE_USER.name();
		String status = MemberStatus.ACTIVE.name();
		String nickname = profileResponse.nickname();

		String accessToken = jwtUtil.createAccessToken(memberId, email, newRole, status, nickname);
		String refreshToken = jwtUtil.createRefreshToken(memberId);

		refreshTokenRepository.save(RefreshToken.builder()
			.memberId(memberId)
			.token(refreshToken)
			.build());

		ResponseCookie accessCookie = CookieUtil.createAccessTokenCookie(accessToken);
		ResponseCookie refreshCookie = CookieUtil.createRefreshTokenCookie(refreshToken);

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());

		return CompleteSignupResponse.builder()
			.profile(profileResponse)
			.isOnboardingFinished(false)
			.build();
	}
}
