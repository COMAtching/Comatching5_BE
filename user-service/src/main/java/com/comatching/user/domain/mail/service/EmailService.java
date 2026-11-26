package com.comatching.user.domain.mail.service;

import java.time.Duration;

public interface EmailService {

	Duration SIGNUP_PROOF_TTL = Duration.ofMinutes(30);

	void sendAuthCode(String email, String source);

	void sendPasswordResetCode(String email, String source);

	String verifySignupCode(String email, String code);

	void verifyPasswordResetCode(String email, String code);

	void consumeSignupProof(String email, String proof);
}
