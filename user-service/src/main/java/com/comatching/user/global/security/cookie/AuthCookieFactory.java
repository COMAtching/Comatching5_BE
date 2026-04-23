package com.comatching.user.global.security.cookie;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AuthCookieFactory {

	private static final String ACCESS_TOKEN_COOKIE_NAME = "accessToken";
	private static final String REFRESH_TOKEN_COOKIE_NAME = "refreshToken";
	private static final String ACCESS_TOKEN_COOKIE_PATH = "/";
	private static final String REFRESH_TOKEN_COOKIE_PATH = "/api/auth";

	private final boolean secure;
	private final String domain;
	private final String sameSite;

	public AuthCookieFactory(
		@Value("${auth.cookie.secure:false}") boolean secure,
		@Value("${auth.cookie.domain:}") String domain,
		@Value("${auth.cookie.same-site:Lax}") String sameSite
	) {
		this.secure = secure;
		this.domain = domain;
		this.sameSite = sameSite;
	}

	public ResponseCookie createAccessTokenCookie(String accessToken) {
		ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, accessToken)
			.path(ACCESS_TOKEN_COOKIE_PATH)
			.httpOnly(true)
			.secure(secure)
			.maxAge(Duration.ofDays(1).toSeconds())
			.sameSite(sameSite);
		applyDomainIfPresent(builder);
		return builder.build();
	}

	public ResponseCookie createRefreshTokenCookie(String refreshToken) {
		ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
			.path(REFRESH_TOKEN_COOKIE_PATH)
			.httpOnly(true)
			.secure(secure)
			.maxAge(Duration.ofDays(7).toSeconds())
			.sameSite(sameSite);
		applyDomainIfPresent(builder);
		return builder.build();
	}

	public ResponseCookie createExpiredCookie(String cookieName) {
		String cookiePath = ACCESS_TOKEN_COOKIE_NAME.equals(cookieName)
			? ACCESS_TOKEN_COOKIE_PATH
			: REFRESH_TOKEN_COOKIE_PATH;

		ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookieName, "")
			.path(cookiePath)
			.httpOnly(true)
			.secure(secure)
			.maxAge(0)
			.sameSite(sameSite);
		applyDomainIfPresent(builder);
		return builder.build();
	}

	private void applyDomainIfPresent(ResponseCookie.ResponseCookieBuilder builder) {
		if (StringUtils.hasText(domain)) {
			builder.domain(domain);
		}
	}
}
