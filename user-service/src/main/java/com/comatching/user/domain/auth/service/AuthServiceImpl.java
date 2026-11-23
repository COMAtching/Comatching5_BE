package com.comatching.user.domain.auth.service;

import java.net.URI;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.SocialType;
import com.comatching.common.exception.BusinessException;
import com.comatching.user.domain.auth.dto.ChangePasswordRequest;
import com.comatching.user.domain.auth.dto.ResetPasswordRequest;
import com.comatching.user.domain.auth.dto.TokenResponse;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.global.exception.UserErrorCode;
import com.comatching.user.global.security.oauth2.provider.kakao.config.KakaoProperties;
import com.comatching.user.global.security.oauth2.provider.kakao.unlink.KakaoAuthClient;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
	private final LoginSessionService sessions;
	private final MemberService memberService;
	private final EmailService emailService;
	private final PasswordEncoder passwordEncoder;
	private final KakaoAuthClient kakaoAuthClient;
	private final KakaoProperties kakaoProperties;

	@Override
	public TokenResponse reissue(String refreshToken) {
		return sessions.reissue(refreshToken);
	}

	@Override
	public void logout(String accessToken, String refreshToken) {
		sessions.logout(accessToken, refreshToken);
	}

	@Override
	public void resetPassword(ResetPasswordRequest request) {
		emailService.verifyPasswordResetCode(request.email(), request.authCode());
		memberService.updatePassword(request.email(), passwordEncoder.encode(request.newPassword()));
	}

	@Override
	@Transactional
	public void changePassword(Long memberId, ChangePasswordRequest request) {
		Member member = memberService.getMemberByIdForUpdate(memberId);
		if (!passwordEncoder.matches(request.currentPassword(), member.getPassword())) {
			throw new BusinessException(UserErrorCode.PASSWORD_NOT_MATCH);
		}
		memberService.updatePassword(member.getEmail(), passwordEncoder.encode(request.newPassword()));
	}

	@Override
	@Transactional
	public void withdraw(Long memberId) {
		Member member = memberService.getMemberByIdForUpdate(memberId);
		String kakaoId = member.getSocialType() == SocialType.KAKAO ? member.getSocialId() : null;
		memberService.withdrawMember(memberId);
		if (kakaoId != null) {
			kakaoAuthClient.unlink(URI.create(kakaoProperties.unlinkUrl()), "KakaoAK " + kakaoProperties.adminKey(),
				kakaoProperties.unlinkTargetIdType(), kakaoId);
		}
	}
}
