package com.comatching.notification.domain.service.email;

public interface EmailService {

	void sendWelcomeEmail(String toEmail, String nickname);
	void sendWithdrawalEmail(String toEmail);
}
