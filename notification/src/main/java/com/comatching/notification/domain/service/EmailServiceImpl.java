package com.comatching.notification.domain.service;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService{

	private final JavaMailSender mailSender;
	private final SpringTemplateEngine templateEngine;

	@Override
	public void sendWelcomeEmail(String toEmail, String nickname) {
		sendEmail(toEmail, "Comatching 회원가입을 축하합니다! 🎉", "welcome-email", nickname);
	}

	@Override
	public void sendWithdrawalEmail(String toEmail) {
		sendEmail(toEmail, "Comatching 이용해 주셔서 감사합니다.", "goodbye-email", null);
	}

	private void sendEmail(String toEmail, String subject, String templateName, String nickname) {
		MimeMessage mimeMessage = mailSender.createMimeMessage();
		try {
			Context context = new Context();
			context.setVariable("nickname", nickname != null ? nickname : "회원");

			String htmlContent = templateEngine.process(templateName, context);

			MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
			helper.setTo(toEmail);
			helper.setSubject(subject);
			helper.setText(htmlContent, true);

			mailSender.send(mimeMessage);
			log.info("Email sent to: {} (Type: {})", toEmail, templateName);

		} catch (MessagingException e) {
			log.error("Failed to send email to {}", toEmail, e);
		}
	}
}
