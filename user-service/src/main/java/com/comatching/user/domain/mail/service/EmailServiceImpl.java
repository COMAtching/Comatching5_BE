package com.comatching.user.domain.mail.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
	private static final DefaultRedisScript<Long> CONSUME_IF_MATCHED = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then
			return redis.call('DEL', KEYS[1])
		end
		return 0
		""", Long.class);

	@Override
	public void sendAuthCode(String email) {
		sendEmailProcess(email, EmailType.SIGNUP);
	}

	@Override
	public String verifySignupCode(String email, String code) {
		verifyCodeProcess(email, code, EmailType.SIGNUP);
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		redisTemplate.opsForValue().set(proofKey(proof), email, SIGNUP_PROOF_TTL);
		return proof;
	}

	@Override
	public void consumeSignupProof(String email, String proof) {
		if (proof == null || proof.length() != 43 || !consume(proofKey(proof), email)) {
			throw new BusinessException(UserErrorCode.EMAIL_NOT_AUTHENTICATED);
		}
	}

	private static String proofKey(String proof) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(proof.getBytes(StandardCharsets.UTF_8));
			return "auth:signup-proof:" + HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private boolean consume(String key, String expected) {
		return Long.valueOf(1).equals(redisTemplate.execute(CONSUME_IF_MATCHED, List.of(key), expected));
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
		if (code == null || !consume(key, code.toUpperCase(Locale.ROOT))) {
			throw new BusinessException(UserErrorCode.INVALID_AUTH_CODE);
		}
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
