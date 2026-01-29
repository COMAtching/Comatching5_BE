package com.comatching.user.domain.auth.dto;

public record TokenResponse(
	String accessToken,
	String refreshToken
) {
}
