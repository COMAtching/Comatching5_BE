package com.comatching.user.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.util.JwtUtil;
import com.comatching.user.domain.auth.dto.TokenResponse;
import com.comatching.user.domain.auth.entity.RefreshToken;
import com.comatching.user.domain.auth.repository.RefreshTokenRepository;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.service.MemberService;
import com.comatching.user.global.security.oauth2.provider.kakao.config.KakaoProperties;
import com.comatching.user.global.security.oauth2.provider.kakao.unlink.KakaoAuthClient;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl 테스트")
class AuthServiceImplTest {

	@InjectMocks
	private AuthServiceImpl authService;

	@Mock
	private JwtUtil jwtUtil;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private MemberService memberService;

	@Mock
	private EmailService emailService;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private KakaoAuthClient kakaoAuthClient;

	@Mock
	private KakaoProperties kakaoProperties;

	@Mock
	private AccessTokenDenylistService accessTokenDenylistService;

	@Test
	@DisplayName("로그아웃 시 refresh token을 삭제하고 현재 access token을 revoke한다")
	void shouldRevokeAccessTokenOnLogout() {
		// given
		String accessToken = "access-token";
		String refreshToken = "refresh-token";
		Claims claims = Jwts.claims().setSubject("100");
		given(jwtUtil.parseToken(refreshToken)).willReturn(claims);
		given(refreshTokenRepository.existsById(100L)).willReturn(true);

		// when
		authService.logout(accessToken, refreshToken);

		// then
		then(accessTokenDenylistService).should().revoke(accessToken);
		then(refreshTokenRepository).should().deleteById(100L);
	}

	@Test
	@DisplayName("탈퇴 성공 후 refresh token을 삭제하고 현재 access token을 revoke한다")
	void shouldRevokeTokensAfterWithdraw() {
		// given
		Member member = Member.builder()
			.email("test@example.com")
			.password("encoded")
			.role(MemberRole.ROLE_USER)
			.status(MemberStatus.ACTIVE)
			.build();
		given(memberService.getMemberById(100L)).willReturn(member);

		// when
		authService.withdraw(100L, "access-token");

		// then
		then(memberService).should().withdrawMember(100L);
		then(refreshTokenRepository).should().deleteById(100L);
		then(accessTokenDenylistService).should().revoke("access-token");
	}

	@Test
	@DisplayName("refresh token 재발급 시 JwtUtil이 만든 새 토큰을 반환하고 저장한다")
	void shouldReturnReissuedTokens() {
		// given
		String oldRefreshToken = "old-refresh-token";
		Claims claims = Jwts.claims().setSubject("100");
		Member member = Member.builder()
			.email("test@example.com")
			.password("encoded")
			.role(MemberRole.ROLE_USER)
			.status(MemberStatus.ACTIVE)
			.build();
		given(jwtUtil.parseToken(oldRefreshToken)).willReturn(claims);
		given(refreshTokenRepository.findById(100L))
			.willReturn(Optional.of(RefreshToken.builder().memberId(100L).token(oldRefreshToken).build()));
		given(memberService.getMemberById(100L)).willReturn(member);
		given(jwtUtil.createAccessToken(100L, "test@example.com", "ROLE_USER", "ACTIVE", null))
			.willReturn("new-access-token");
		given(jwtUtil.createRefreshToken(100L)).willReturn("new-refresh-token");

		// when
		TokenResponse response = authService.reissue(oldRefreshToken);

		// then
		ArgumentCaptor<RefreshToken> refreshTokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		then(refreshTokenRepository).should().save(refreshTokenCaptor.capture());
		assertThat(refreshTokenCaptor.getValue().getMemberId()).isEqualTo(100L);
		assertThat(refreshTokenCaptor.getValue().getToken()).isEqualTo("new-refresh-token");
		assertThat(response.accessToken()).isEqualTo("new-access-token");
		assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
	}
}
