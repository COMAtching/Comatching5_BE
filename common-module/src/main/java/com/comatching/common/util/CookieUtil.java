package com.comatching.common.util;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

public class CookieUtil {

	private CookieUtil() {
	}

	// Access Token 쿠키
	public static ResponseCookie createAccessTokenCookie(String accessToken) {
		return ResponseCookie.from("accessToken", accessToken)
			.path("/")
			.httpOnly(true)
			.secure(true)
			.maxAge(Duration.ofDays(1).toSeconds())
			.sameSite("Lax")
			.domain("comatching.site")
			.build();
	}

	// Refresh Token 쿠키
	public static ResponseCookie createRefreshTokenCookie(String refreshToken) {
		return ResponseCookie.from("refreshToken", refreshToken)
			.path("/api/auth")
			.httpOnly(true)
			.secure(true)
			.maxAge(Duration.ofDays(7).toSeconds())
			.sameSite("Lax")
			.domain("comatching.site")
			.build();
	}

	// 로그아웃 시 쿠키 삭제
	public static ResponseCookie createExpiredCookie(String cookieName) {
		return ResponseCookie.from(cookieName, "")
			.path(cookieName.equals("accessToken") ? "/" :"/api/auth")
			.httpOnly(true)
			.maxAge(0)
			.domain("comatching.site")
			.build();
	}
}