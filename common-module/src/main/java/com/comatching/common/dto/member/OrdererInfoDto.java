package com.comatching.common.dto.member;

public record OrdererInfoDto(
	Long memberId,
	String realName,
	String nickname
) {
}
