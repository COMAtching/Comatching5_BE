package com.comatching.auth.global.security.refresh.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.auth.domain.dto.TokenResponse;
import com.comatching.auth.global.security.refresh.RefreshToken;
import com.comatching.auth.global.security.refresh.repository.RefreshTokenRepository;
import com.comatching.auth.infra.client.MemberServiceClient;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.util.JwtUtil;

import io.jsonwebtoken.Claims;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

	@Mock
	private JwtUtil jwtUtil;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private MemberServiceClient memberServiceClient;

	@InjectMocks
	private RefreshTokenService refreshTokenService;

	@Test
	void reissue_Success() {
		//given
		String oldRefreshToken = "old_refresh_token";
		Long memberId = 1L;
		Claims mockClaims = mock(Claims.class);

		given(jwtUtil.parseToken(oldRefreshToken)).willReturn(mockClaims);
		given(mockClaims.getSubject()).willReturn(String.valueOf(memberId));

		RefreshToken redisToken = RefreshToken.builder()
			.memberId(memberId)
			.token(oldRefreshToken)
			.build();
		given(refreshTokenRepository.findById(memberId)).willReturn(Optional.of(redisToken));

		MemberLoginDto memberDto = new MemberLoginDto(memberId, "test@comatching.com", "pw", "ROLE_USER", "ACTIVE");
		given(memberServiceClient.getMemberById(memberId)).willReturn(memberDto);
		given(jwtUtil.createAccessToken(anyLong(), anyString(), anyString(), anyString())).willReturn("new_access");
		given(jwtUtil.createRefreshToken(anyLong())).willReturn("new_refresh");

		//when
		TokenResponse response = refreshTokenService.reissue(oldRefreshToken);

		//then
		assertThat(response.accessToken()).isEqualTo("new_access");
		assertThat(response.refreshToken()).isEqualTo("new_refresh");
		verify(refreshTokenRepository).save(any(RefreshToken.class));

	}

	@Test
	void reissue_Fail_TokenMismatch() {
		//given
		String stolenToken = "stolen_token";
		Long memberId = 1L;
		Claims mockClaims = mock(Claims.class);

		given(jwtUtil.parseToken(stolenToken)).willReturn(mockClaims);
		given(mockClaims.getSubject()).willReturn(String.valueOf(memberId));

		RefreshToken redisToken = RefreshToken.builder()
			.memberId(memberId)
			.token("original_token")
			.build();
		given(refreshTokenRepository.findById(memberId)).willReturn(Optional.of(redisToken));

		// when & then
		assertThatThrownBy(() -> refreshTokenService.reissue(stolenToken))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining("유효하지 않은 리프레시 토큰입니다. 다시 로그인해주세요.");

		verify(refreshTokenRepository).delete(redisToken);
	}

	@Test
	void logout_success() {
		//given
		String token = "valid_token";
		Long memberId = 1L;
		Claims claims = mock(Claims.class);

		given(jwtUtil.parseToken(token)).willReturn(claims);
		given(claims.getSubject()).willReturn(String.valueOf(memberId));
		given(refreshTokenRepository.existsById(memberId)).willReturn(true);

		//when
		refreshTokenService.logout(token);

		//then
		verify(refreshTokenRepository).deleteById(memberId);
	}

	@Test
	void logout_invalidToken() {
		// given
		String invalidToken = "invalid_token";

		given(jwtUtil.parseToken(invalidToken))
			.willThrow(new BusinessException(GeneralErrorCode.UNAUTHORIZED, "Invalid Token"));

		// when
		refreshTokenService.logout(invalidToken);

		// then
		verify(refreshTokenRepository, never()).deleteById(anyLong());
	}

}