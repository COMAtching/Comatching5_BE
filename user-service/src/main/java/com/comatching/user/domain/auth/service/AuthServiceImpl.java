package com.comatching.user.domain.auth.service;

import java.net.URI;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.user.domain.auth.dto.ChangePasswordRequest;
import com.comatching.user.domain.auth.dto.ResetPasswordRequest;
import com.comatching.user.domain.auth.dto.TokenResponse;
import com.comatching.user.domain.auth.entity.RefreshToken;
import com.comatching.user.domain.auth.repository.RefreshTokenRepository;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.global.exception.UserErrorCode;
import com.comatching.user.global.security.oauth2.provider.kakao.config.KakaoProperties;
import com.comatching.user.global.security.oauth2.provider.kakao.unlink.KakaoAuthClient;
import com.comatching.common.domain.enums.SocialType;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.util.JwtUtil;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService{

	private final JwtUtil jwtUtil;
	private final RefreshTokenRepository refreshTokenRepository;
	private final MemberService memberService;
	private final EmailService emailService;
	private final PasswordEncoder passwordEncoder;
	private final KakaoAuthClient kakaoAuthClient;
	private final KakaoProperties kakaoProperties;

	@Override
	public TokenResponse reissue(String refreshToken) {

		// JWT 유효성 검사
		Claims claims = jwtUtil.parseToken(refreshToken);
		long memberId = Long.parseLong(claims.getSubject());

		// Redis에 저장된 토큰과 대조
		RefreshToken redisToken = refreshTokenRepository.findById(memberId)
			.orElseThrow(() -> new BusinessException(GeneralErrorCode.UNAUTHORIZED, "로그인 정보가 없습니다. 다시 로그인해주세요."));

		if (!redisToken.getToken().equals(refreshToken)) {
			// 토큰 불일치 시 탈취 가능성이 있으므로 Redis 토큰 삭제 후 예외 발생
			refreshTokenRepository.delete(redisToken);
			throw new BusinessException(GeneralErrorCode.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다. 다시 로그인해주세요.");
		}

		// 새로운 토큰 세트 생성
		Member member = memberService.getMemberById(memberId);
		MemberLoginDto memberDto = toLoginDto(member);

		String newAccessToken = jwtUtil.createAccessToken(
			memberId, memberDto.email(), memberDto.role(), memberDto.status(), memberDto.nickname()
		);
		String newRefreshToken = jwtUtil.createRefreshToken(memberId);

		// redis 갱신
		refreshTokenRepository.save(RefreshToken.builder()
			.memberId(memberId)
			.token(newRefreshToken)
			.build());

		return new TokenResponse(newAccessToken, newRefreshToken);
	}

	@Override
	public void logout(String refreshToken) {
		if (refreshToken == null) {
			return;
		}

		try {
			// 토큰 파싱하여 ID 추출
			Claims claims = jwtUtil.parseToken(refreshToken);
			Long memberId = Long.parseLong(claims.getSubject());

			// Redis에 해당 ID로 저장된 토큰이 있다면 삭제
			if (refreshTokenRepository.existsById(memberId)) {
				refreshTokenRepository.deleteById(memberId);
			}
		} catch (Exception e) {
			log.warn("로그아웃 처리 중 토큰 오류 발생 (무시하고 진행): {}", e.getMessage());
		}
	}

	@Override
	public void resetPassword(ResetPasswordRequest request) {

		emailService.verifyPasswordResetCode(request.email(), request.authCode());

		String encryptedPassword = passwordEncoder.encode(request.newPassword());

		memberService.updatePassword(request.email(), encryptedPassword);
	}

	@Override
	public void changePassword(Long memberId, ChangePasswordRequest request) {
		Member member = memberService.getMemberById(memberId);
		MemberLoginDto memberDto = toLoginDto(member);

		if (!passwordEncoder.matches(request.currentPassword(), memberDto.password())) {
			throw new BusinessException(UserErrorCode.PASSWORD_NOT_MATCH);
		}

		String newEncryptedPassword = passwordEncoder.encode(request.newPassword());

		memberService.updatePassword(memberDto.email(), newEncryptedPassword);
	}

	@Override
	public void withdraw(Long memberId) {
		Member member = memberService.getMemberById(memberId);

		if (member.getSocialType() == SocialType.KAKAO) {
			unlinkKakao(member.getSocialId());
		}

		memberService.withdrawMember(memberId);

		refreshTokenRepository.deleteById(memberId);
	}

	private void unlinkKakao(String socialId) {
		kakaoAuthClient.unlink(
			URI.create(kakaoProperties.unlinkUrl()),
			"KakaoAK " + kakaoProperties.adminKey(),
			kakaoProperties.unlinkTargetIdType(),
			socialId
		);
	}

	private MemberLoginDto toLoginDto(Member member) {
		return MemberLoginDto.builder()
			.id(member.getId())
			.email(member.getEmail())
			.password(member.getPassword())
			.role(member.getRole().name())
			.status(member.getStatus().name())
			.socialType(member.getSocialType())
			.socialId(member.getSocialId())
			.nickname(member.getProfile() != null ? member.getProfile().getNickname() : null)
			.build();
	}
}
