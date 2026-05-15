package com.comatching.common.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailVerifyRequest(
	@NotBlank
	@Email
	String email,

	@NotBlank
	String code
) {
}
