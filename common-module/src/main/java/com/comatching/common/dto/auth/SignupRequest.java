package com.comatching.common.dto.auth;

import lombok.Builder;

@Builder
public record SignupRequest(
	String email,
	String password
) {
}
