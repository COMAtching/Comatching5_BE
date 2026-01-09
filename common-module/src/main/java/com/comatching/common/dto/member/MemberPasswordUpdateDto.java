package com.comatching.common.dto.member;

public record MemberPasswordUpdateDto(
	String email,
	String encryptedPassword
) {}
