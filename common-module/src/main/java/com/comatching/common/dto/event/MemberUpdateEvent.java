package com.comatching.common.dto.event;

import com.comatching.common.domain.enums.MemberStatus;

import lombok.Builder;

@Builder
public record MemberUpdateEvent(
	Long memberId,
	String nickname,
	String profileImageUrl,
	MemberStatus status
) {
}
