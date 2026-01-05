package com.comatching.auth.global.security.refresh.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.auth.domain.dto.TokenResponse;
import com.comatching.auth.global.security.refresh.RefreshToken;
import com.comatching.auth.global.security.refresh.repository.RefreshTokenRepository;
import com.comatching.auth.infra.client.MemberServiceClient;
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
public class RefreshTokenService {

	private final JwtUtil jwtUtil;
	private final RefreshTokenRepository refreshTokenRepository;
	private final MemberServiceClient memberServiceClient;

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
		MemberLoginDto memberDto = memberServiceClient.getMemberById(memberId);

		String newAccessToken = jwtUtil.createAccessToken(
			memberId, memberDto.email(), memberDto.role(), memberDto.status()
		);
		String newRefreshToken = jwtUtil.createRefreshToken(memberId);

		// redis 갱신
		refreshTokenRepository.save(RefreshToken.builder()
			.memberId(memberId)
			.token(newRefreshToken)
			.build());

		return new TokenResponse(newAccessToken, newRefreshToken);
	}

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

}
