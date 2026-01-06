package com.comatching.common.dto.auth;

public record EmailVerifyRequest(
	String email,
	String code
) {
}
