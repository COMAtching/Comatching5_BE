package com.comatching.common.dto.auth;

import com.comatching.common.domain.enums.MemberRole;

public record MemberCreateRequest(
	String email,
	String password,
	MemberRole role
) {
}
