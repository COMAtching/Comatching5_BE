package com.comatching.auth.domain.controller;

import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.auth.domain.dto.TokenResponse;
import com.comatching.auth.domain.service.AuthService;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.util.CookieUtil;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@PostMapping("/reissue")
	public ResponseEntity<ApiResponse<Void>> reissue(
		@CookieValue(name = "refreshToken") String refreshToken,
		HttpServletResponse response
	) {

		TokenResponse tokenResponse = authService.reissue(refreshToken);

		ResponseCookie accessCookie = CookieUtil.createAccessTokenCookie(tokenResponse.accessToken());
		ResponseCookie refreshCookie = CookieUtil.createRefreshTokenCookie(tokenResponse.refreshToken());

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());

		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<Void>> logout(
		@CookieValue(name = "refreshToken", required = false) String refreshToken,
		HttpServletResponse response) {

		authService.logout(refreshToken);

		ResponseCookie accessCookie = CookieUtil.createExpiredCookie("accessToken");
		ResponseCookie refreshCookie = CookieUtil.createExpiredCookie("refreshToken");

		response.addHeader("Set-Cookie", accessCookie.toString());
		response.addHeader("Set-Cookie", refreshCookie.toString());


		return ResponseEntity.ok(ApiResponse.ok());
	}
}
