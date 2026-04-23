package com.comatching.user.global.security.handler;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.comatching.user.global.security.UserPrincipal;
import com.comatching.user.global.security.cookie.AuthCookieFactory;
import com.comatching.user.domain.auth.entity.RefreshToken;
import com.comatching.user.domain.auth.repository.RefreshTokenRepository;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

	private final JwtUtil jwtUtil;
	private final ObjectMapper objectMapper;
	private final RefreshTokenRepository refreshTokenRepository;
	private final AuthCookieFactory authCookieFactory;

	@Value("${client.url}")
	private String clientUrl;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
		Authentication authentication) throws IOException, ServletException {

		UserPrincipal principal = (UserPrincipal)authentication.getPrincipal();
		String role = principal.getRole();

		// 토큰 생성
		String accessToken = jwtUtil.createAccessToken(principal.getId(), principal.getUsername(), principal.getRole(),
			principal.getStatus(), principal.getNickname());
		String refreshToken = jwtUtil.createRefreshToken(principal.getId());

		// Redis 저장
		RefreshToken redisToken = RefreshToken.builder()
			.memberId(principal.getId())
			.token(refreshToken)
			.build();
		refreshTokenRepository.save(redisToken);

		// 쿠키 설정
		ResponseCookie accessCookie = authCookieFactory.createAccessTokenCookie(accessToken);
		ResponseCookie refreshCookie = authCookieFactory.createRefreshTokenCookie(refreshToken);

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());

		if (role.equals("ROLE_ADMIN")) {
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			ApiResponse<Void> apiResponse = ApiResponse.ok();
			objectMapper.writeValue(response.getWriter(), apiResponse);
			return;
		}

		if (role.equals("ROLE_GUEST")) {
			response.sendRedirect(clientUrl + "/onboarding");
		} else {
			response.sendRedirect(clientUrl + "/main");
		}
	}
}
