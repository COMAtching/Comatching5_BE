package com.comatching.user.global.security.oauth2.handler;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.comatching.user.global.security.oauth2.user.CustomOAuth2User;
import com.comatching.user.domain.auth.entity.RefreshToken;
import com.comatching.user.domain.auth.repository.RefreshTokenRepository;
import com.comatching.common.util.CookieUtil;
import com.comatching.common.util.JwtUtil;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CustomOAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	private final JwtUtil jwtUtil;
	private final RefreshTokenRepository refreshTokenRepository;

	@Value("${client.url}")
	private String clientUrl;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
		Authentication authentication) throws IOException, ServletException {

		// UserPrincipal(CustomOAuth2User) 추출
		CustomOAuth2User userPrincipal = (CustomOAuth2User)authentication.getPrincipal();

		Long memberId = userPrincipal.getId();
		String email = userPrincipal.getUsername();
		String role = userPrincipal.getRole();
		String status = userPrincipal.getStatus();
		String nickname = userPrincipal.getNickname();

		// 토큰 발급
		String accessToken = jwtUtil.createAccessToken(memberId, email, role, status, nickname);
		String refreshToken = jwtUtil.createRefreshToken(memberId);

		// Refresh Token Redis 저장
		refreshTokenRepository.save(RefreshToken.builder()
			.memberId(memberId)
			.token(refreshToken)
			.build());

		// 쿠키 설정
		ResponseCookie accessCookie = CookieUtil.createAccessTokenCookie(accessToken);
		ResponseCookie refreshCookie = CookieUtil.createRefreshTokenCookie(refreshToken);

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());

		getRedirectStrategy().sendRedirect(request, response, clientUrl + "/oauth2/callback/success");
	}
}
