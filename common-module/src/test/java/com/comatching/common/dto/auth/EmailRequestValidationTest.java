package com.comatching.common.dto.auth;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class EmailRequestValidationTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void shouldRejectInvalidEmailForAuthCodeRequest() {
		assertThat(validator.validate(new EmailRequest("invalid-email"))).isNotEmpty();
	}

	@Test
	void shouldRejectInvalidEmailForVerifyRequest() {
		assertThat(validator.validate(new EmailVerifyRequest("invalid-email", "ABC123"))).isNotEmpty();
	}

	@Test
	void shouldRejectBlankVerifyCode() {
		assertThat(validator.validate(new EmailVerifyRequest("user@example.com", ""))).isNotEmpty();
	}
}
