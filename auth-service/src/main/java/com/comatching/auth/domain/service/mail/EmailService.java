package com.comatching.auth.domain.service.mail;

public interface EmailService {

	void sendAuthCode(String email);

	void verifyCode(String email, String code);

	boolean isVerified(String email);
}
