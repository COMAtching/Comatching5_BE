package com.comatching.auth.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EmailType {
	SIGNUP("AuthCode:", "Verified:", "Comatching 회원가입 인증코드", "auth-code"),
	PASSWORD_RESET("PwReset:", "PwVerified:", "Comatching 비밀번호 재설정 인증코드", "password-code");

	private final String prefix;
	private final String verifiedPrefix;
	private final String subject;
	private final String templateName;
}
