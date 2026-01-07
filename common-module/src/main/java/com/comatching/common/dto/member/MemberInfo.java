package com.comatching.common.dto.member;

public record MemberInfo(
	Long memberId,
	String email,
	String role
) {
}
