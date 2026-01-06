package com.comatching.auth.domain.service.mail;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import com.comatching.auth.global.exception.AuthErrorCode;
import com.comatching.common.exception.BusinessException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

	private final JavaMailSender mailSender;
	private final StringRedisTemplate redisTemplate;
	private final SpringTemplateEngine templateEngine;
	private final SecureRandom secureRandom = new SecureRandom();

	private static final String AUTH_CODE_PREFIX = "AuthCode:";
	private static final String VERIFIED_PREFIX = "Verified:";
	private static final String CHARACTERS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
	private static final int CODE_LENGTH = 6;
	private static final long CODE_EXPIRATION = Duration.ofMinutes(5).toSeconds();
	private static final long VERIFIED_EXPIRATION = Duration.ofMinutes(30).toSeconds();

	@Override
	public void sendAuthCode(String email) {

		String authCode = createCode();
		log.info("create authcode={}", authCode);

		redisTemplate.opsForValue().set(
			AUTH_CODE_PREFIX + email,
			authCode,
			CODE_EXPIRATION,
			TimeUnit.SECONDS
		);

		try {
			sendHtmlEmail(email, "Comatching 인증 코드", authCode);
		} catch (MessagingException e) {
			throw new BusinessException(AuthErrorCode.SEND_EMAIL_FAILED);
		}

	}

	@Override
	public void verifyCode(String email, String code) {

		String key = AUTH_CODE_PREFIX + email;
		String storedCode = redisTemplate.opsForValue().get(AUTH_CODE_PREFIX + email);

		log.info("storedCode={}", storedCode);

		if (storedCode == null || !storedCode.equals(code)) {
			throw new BusinessException(AuthErrorCode.INVALID_AUTH_CODE);
		}

		redisTemplate.delete(key);
		redisTemplate.opsForValue().set(
			VERIFIED_PREFIX + email,
			"true",
			VERIFIED_EXPIRATION,
			TimeUnit.SECONDS
		);
	}

	@Override
	public boolean isVerified(String email) {
		return Boolean.TRUE.toString().equals(
			redisTemplate.opsForValue().get(VERIFIED_PREFIX + email)
		);
	}

	private String createCode() {

		StringBuilder sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			int randomIndex = secureRandom.nextInt(CHARACTERS.length());
			sb.append(CHARACTERS.charAt(randomIndex));
		}

		return sb.toString();
	}

	private void sendHtmlEmail(String to, String subject, String authCode) throws MessagingException {

		MimeMessage message = mailSender.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

		Context context = new Context();
		context.setVariable("code", authCode);

		String htmlContent = templateEngine.process("auth-code", context);

		helper.setTo(to);
		helper.setSubject(subject);
		helper.setText(htmlContent, true);

		mailSender.send(message);
	}
}
