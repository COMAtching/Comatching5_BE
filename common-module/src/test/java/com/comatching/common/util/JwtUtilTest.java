package com.comatching.common.util;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;

class JwtUtilTest {

	private static final String SECRET_KEY = "c2lsdmVybmluZS10ZWNoLXNwcmluZy1ib290LWp3dC10dXRvcmlhbC1zZWNyZXQtc2lsdmVybmluZS10ZWNoLXNwcmluZy1ib290LWp3dC10dXRvcmlhbC1zZWNyZXQK";
	private final JwtUtil jwtUtil = new JwtUtil(SECRET_KEY, 3600000L, 3600000L);

	Long memberId;
	String email;
	String role;
	String status;
	String nickname;

	@BeforeEach
	void setUp() {
		memberId = 1L;
		email = "test@test.com";
		role = "ROLE_USER";
		status = "ACTIVE";
		nickname = "test";
	}

	@Test
	void createAndParseToken() {
		//when
		String token = jwtUtil.createAccessToken(memberId, email, role, status, nickname);
		Claims claims = jwtUtil.parseToken(token);

		//then
		assertThat(token).isNotNull();
		assertThat(claims.getSubject()).isEqualTo("1");
		assertThat(claims.get("email")).isEqualTo("test@test.com");
	}

	@Test
	void validateToken() {
		//given
		String token = jwtUtil.createAccessToken(memberId, email, role, status, nickname);

		//when
		boolean isValid = jwtUtil.validateToken(token);

		//then
		assertThat(isValid).isTrue();

	}

}