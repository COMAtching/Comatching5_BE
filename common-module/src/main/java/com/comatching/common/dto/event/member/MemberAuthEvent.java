package com.comatching.common.dto.event.member;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record MemberAuthEvent(
	Long memberId,
	String email,
	String nickname,
	EventType type
) {
	// 가입과 탈퇴는 회원 ID별로 각각 한 번 발생한다. 구형 JSON도 같은 ID를 복원한다.
	@JsonProperty(value = "eventId", access = JsonProperty.Access.READ_ONLY)
	public String eventId() {
		return type.eventId(memberId);
	}

	public enum EventType {
		SIGNUP("member-signup"), WITHDRAWAL("member-withdraw");

		private final String prefix;

		EventType(String prefix) {
			this.prefix = prefix;
		}

		String eventId(Long memberId) {
			return prefix + ":" + Objects.requireNonNull(memberId, "memberId");
		}
	}
}
