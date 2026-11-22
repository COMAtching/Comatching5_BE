package com.comatching.common.dto.event.matching;

import java.time.LocalDateTime;
import java.util.Objects;

/** 매칭 후보 복구에 필요한 회원 상태와 현재 프로필. 조회 장애는 이 응답으로 바꾸지 않는다. */
public record MatchingProfileSnapshot(Long memberId, State state, ProfileUpdatedMatchingEvent profile,
	LocalDateTime withdrawnAt) {
	public enum State { ACTIVE, WITHDRAWN, NO_ACTIVE_PROFILE }

	public MatchingProfileSnapshot {
		Objects.requireNonNull(memberId);
		Objects.requireNonNull(state);
		if (state == State.ACTIVE && (profile == null || !memberId.equals(profile.memberId())
			|| profile.profileId() == null || profile.isMatchable() == null
			|| profile.profileVersion() == null || profile.profileVersion() < 0)) {
			throw new IllegalArgumentException("매칭 복구에 필요한 활성 프로필 값이 잘못되었습니다.");
		}
		if (state == State.WITHDRAWN) Objects.requireNonNull(withdrawnAt);
	}
}
