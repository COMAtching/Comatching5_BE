package com.comatching.common.dto.auth;

import lombok.Builder;

@Builder
public record MemberLoginDto(
	Long id,
	String email,
	String password,
	String role,
	String status
) {
}
