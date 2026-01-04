package com.comatching.common.util;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class CookieUtilTest {

	@Test
	void createAccessTokenCookie() {
		// given
		String token = "access-token-value";

		// when
		ResponseCookie cookie = CookieUtil.createAccessTokenCookie(token);

		// then
		assertThat(cookie.getValue()).isEqualTo(token);
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getPath()).isEqualTo("/");
		assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(Duration.ofDays(1).toSeconds());
		assertThat(cookie.getSameSite()).isEqualTo("Strict");
	}

	@Test
	void createRefreshTokenCookie() {
		// given
		String token = "refresh-token-value";

		// when
		ResponseCookie cookie = CookieUtil.createRefreshTokenCookie(token);

		// then
		assertThat(cookie.getValue()).isEqualTo(token);
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getPath()).isEqualTo("/auth/reissue");
		assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(Duration.ofDays(7).toSeconds());
	}

	@Test
	void createExpiredCookie() {
		// given
		String cookieName = "accessToken";

		// when
		ResponseCookie cookie = CookieUtil.createExpiredCookie(cookieName);

		// then
		assertThat(cookie.getName()).isEqualTo(cookieName);
		assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(0);
		assertThat(cookie.getValue()).isEmpty();
	}
}