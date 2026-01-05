package com.comatching.auth.domain.dto;

public record TokenResponse(
	String accessToken,
	String refreshToken
) {
}
