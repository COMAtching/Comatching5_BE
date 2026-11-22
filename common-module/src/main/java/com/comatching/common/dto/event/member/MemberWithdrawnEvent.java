package com.comatching.common.dto.event.member;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;

public record MemberWithdrawnEvent(
	Long memberId,
	String email,
	LocalDateTime withdrawnAt
) {
	@JsonProperty(value = "eventId", access = JsonProperty.Access.READ_ONLY)
	public String eventId() {
		return MemberAuthEvent.EventType.WITHDRAWAL.eventId(memberId);
	}
}
