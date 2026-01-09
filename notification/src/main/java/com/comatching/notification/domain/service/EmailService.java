package com.comatching.notification.domain.service;

public interface EmailService {

	void sendWelcomeEmail(String toEmail, String nickname);
	void sendWithdrawalEmail(String toEmail);
}
