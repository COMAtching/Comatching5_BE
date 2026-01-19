package com.comatching.common.dto.auth;

import com.comatching.common.domain.enums.SocialType;

import lombok.Builder;

@Builder
public record MemberLoginDto(
	Long id,
	String email,
	String password,
	String role,
	String status,
	String socialId,
	SocialType socialType,
	String nickname
) {
}
