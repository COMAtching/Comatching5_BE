package com.comatching.user.domain.mail.service;

public class VerificationMailLimitException extends RuntimeException {
	private final long retryAfterSeconds;

	public VerificationMailLimitException(long retryAfterSeconds) {
		super("인증 메일 발송 한도에 도달했습니다.");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
