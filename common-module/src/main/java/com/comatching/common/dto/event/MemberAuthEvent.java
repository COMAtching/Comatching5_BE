package com.comatching.common.dto.event;

import lombok.Builder;

@Builder
public record MemberAuthEvent(
	Long memberId,
	String email,
	String nickname,
	EventType type
) {
	public enum EventType {
		SIGNUP, WITHDRAWAL
	}
}
