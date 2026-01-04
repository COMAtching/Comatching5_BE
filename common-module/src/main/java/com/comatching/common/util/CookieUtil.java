package com.comatching.common.util;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

public class CookieUtil {

	private CookieUtil() {}

	// Access Token 쿠키
	public static ResponseCookie createAccessTokenCookie(String accessToken) {
		return ResponseCookie.from("accessToken", accessToken)
			.path("/")
			.httpOnly(true)
			.secure(false) // HTTPS 환경에서는 true
			.maxAge(Duration.ofDays(1).toSeconds())
			.sameSite("Lax")
			.build();
	}

	// Refresh Token 쿠키
	public static ResponseCookie createRefreshTokenCookie(String refreshToken) {
		return ResponseCookie.from("refreshToken", refreshToken)
			.path("/auth/reissue")
			.httpOnly(true)
			.secure(false) // HTTPS 환경에서는 true
			.maxAge(Duration.ofDays(7).toSeconds())
			.sameSite("Lax")
			.build();
	}

	// 로그아웃 시 쿠키 삭제
	public static ResponseCookie createExpiredCookie(String cookieName) {
		return ResponseCookie.from(cookieName, "")
			.path("/")
			.httpOnly(true)
			.maxAge(0) // 즉시 만료
			.build();
	}
}