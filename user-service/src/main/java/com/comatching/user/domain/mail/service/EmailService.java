package com.comatching.user.domain.mail.service;

public interface EmailService {

	void sendAuthCode(String email);

	void sendPasswordResetCode(String email);

	void verifyCode(String email, String code);

	void verifyPasswordResetCode(String email, String code);

	boolean isVerified(String email);
}
