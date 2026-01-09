package com.comatching.auth.domain.service.auth;

import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.auth.domain.service.mail.EmailService;
import com.comatching.auth.global.exception.AuthErrorCode;
import com.comatching.auth.global.security.refresh.RefreshToken;
import com.comatching.auth.global.security.refresh.repository.RefreshTokenRepository;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.auth.MemberCreateRequest;
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
	private final MemberServiceClient memberServiceClient;
	private final PasswordEncoder passwordEncoder;
	private final JwtUtil jwtUtil;
	private final RefreshTokenRepository refreshTokenRepository;

	@Override
	public void signup(SignupRequest request) {

		// 이메일 인증 완료 여부 확인
		if (!emailService.isVerified(request.email())) {
			throw new BusinessException(AuthErrorCode.EMAIL_NOT_AUTHENTICATED, "이메일 인증이 완료되지 않았습니다.");
		}

		String encodedPassword = passwordEncoder.encode(request.password());

		MemberCreateRequest createRequest = new MemberCreateRequest(
			request.email(),
			encodedPassword,
			MemberRole.ROLE_GUEST
		);

		memberServiceClient.createMember(createRequest);
	}

	@Override
	@Transactional
	public ProfileResponse completeSignup(MemberInfo memberInfo, ProfileCreateRequest request,
		HttpServletResponse response) {

		// Member Service에 프로필 생성 요청
		ProfileResponse profileResponse = memberServiceClient.createProfile(memberInfo.memberId(), request);

		Long memberId = profileResponse.memberId();
		String email = profileResponse.email();
		String newRole = MemberRole.ROLE_USER.name();
		String status = MemberStatus.ACTIVE.name();

		String accessToken = jwtUtil.createAccessToken(memberId, email, newRole, status);
		String refreshToken = jwtUtil.createRefreshToken(memberId);

		refreshTokenRepository.save(RefreshToken.builder()
			.memberId(memberId)
			.token(refreshToken)
			.build());

		ResponseCookie accessCookie = CookieUtil.createAccessTokenCookie(accessToken);
		ResponseCookie refreshCookie = CookieUtil.createRefreshTokenCookie(refreshToken);

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());

		return profileResponse;
	}
}
