package com.comatching.common.dto.auth;

import com.comatching.common.domain.enums.SocialType;

public record SocialLoginRequestDto(
	String email,
	String nickname,
	SocialType provider,
	String providerId
) {
}
