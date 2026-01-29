package com.comatching.user.domain.mail.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import com.comatching.user.domain.mail.enums.EmailType;
import com.comatching.user.global.exception.UserErrorCode;
import com.comatching.common.exception.BusinessException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

	private final JavaMailSender mailSender;
	private final StringRedisTemplate redisTemplate;
	private final SpringTemplateEngine templateEngine;
	private final SecureRandom secureRandom = new SecureRandom();

	private static final String CHARACTERS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
	private static final int CODE_LENGTH = 6;
	private static final long CODE_EXPIRATION = Duration.ofMinutes(5).toSeconds();
	private static final long VERIFIED_EXPIRATION = Duration.ofMinutes(30).toSeconds();

	@Override
	public void sendAuthCode(String email) {
		sendEmailProcess(email, EmailType.SIGNUP);
	}

	@Override
	public void verifyCode(String email, String code) {
		verifyCodeProcess(email, code, EmailType.SIGNUP);
	}

	@Override
	public boolean isVerified(String email) {
		return Boolean.TRUE.toString().equals(
			redisTemplate.opsForValue().get(EmailType.SIGNUP.getVerifiedPrefix() + email)
		);
	}

	@Override
	public void sendPasswordResetCode(String email) {
		sendEmailProcess(email, EmailType.PASSWORD_RESET);
	}

	@Override
	public void verifyPasswordResetCode(String email, String code) {
		verifyCodeProcess(email, code, EmailType.PASSWORD_RESET);
	}

	private String createCode() {

		StringBuilder sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			int randomIndex = secureRandom.nextInt(CHARACTERS.length());
			sb.append(CHARACTERS.charAt(randomIndex));
		}

		return sb.toString();
	}

	private void verifyCodeProcess(String email, String code, EmailType type) {
		String key = type.getPrefix() + email;
		String storedCode = redisTemplate.opsForValue().get(key);

		if (storedCode == null || !storedCode.equalsIgnoreCase(code)) {
			throw new BusinessException(UserErrorCode.INVALID_AUTH_CODE);
		}

		redisTemplate.delete(key);
		redisTemplate.opsForValue().set(
			type.getVerifiedPrefix() + email,
			"true",
			VERIFIED_EXPIRATION,
			TimeUnit.SECONDS
		);
	}

	private void sendEmailProcess(String email, EmailType type) {
		String code = createCode();

		redisTemplate.opsForValue().set(
			type.getPrefix() + email,
			code,
			CODE_EXPIRATION,
			TimeUnit.SECONDS
		);

		try {
			sendHtmlEmail(email, type, code);
		} catch (MessagingException e) {
			throw new BusinessException(UserErrorCode.SEND_EMAIL_FAILED);
		}
	}

	private void sendHtmlEmail(String to, EmailType type, String authCode) throws MessagingException {
		MimeMessage message = mailSender.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

		Context context = new Context();
		context.setVariable("code", authCode);

		String htmlContent = templateEngine.process(type.getTemplateName(), context);

		helper.setTo(to);
		helper.setSubject(type.getSubject());
		helper.setText(htmlContent, true);

		mailSender.send(message);
	}
}
