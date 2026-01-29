package com.comatching.user.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetCodeRequest(
	@NotBlank @Email
	String email
) {
}
