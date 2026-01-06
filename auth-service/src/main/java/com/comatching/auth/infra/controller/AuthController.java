package com.comatching.auth.infra.controller;

import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.auth.domain.dto.TokenResponse;
import com.comatching.auth.domain.service.auth.AuthService;
import com.comatching.auth.domain.service.auth.SignupService;
import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.util.CookieUtil;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final SignupService signupService;

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<Void>> signup(@RequestBody SignupRequest request) {
		signupService.signup(request);
		return ResponseEntity.ok(ApiResponse.ok());
	}

	@PostMapping("/signup/profile")
	public ResponseEntity<ProfileResponse> completeSignup(
		@RequestBody ProfileCreateRequest request,
		HttpServletResponse response
	) {
		ProfileResponse result = signupService.completeSignup(request, response);
		return ResponseEntity.ok(result);
	}

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
