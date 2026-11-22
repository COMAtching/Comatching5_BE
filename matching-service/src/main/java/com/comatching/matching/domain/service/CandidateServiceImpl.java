package com.comatching.matching.domain.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.comatching.common.dto.event.matching.MatchingProfileSnapshot;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.matching.infra.client.MemberClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CandidateServiceImpl implements CandidateService {

	private final CandidateProfileStore candidates;
	private final MemberClient members;

	@Override
	public void removeCandidate(Long memberId, LocalDateTime withdrawnAt) {
		candidates.removeCandidate(memberId, withdrawnAt);
	}

	@Override
	public void upsertCandidate(ProfileUpdatedMatchingEvent event) {
		Long version = event.profileVersion();
		if (event.memberId() == null || (version != null && version < 1)) {
			throw new IllegalArgumentException("잘못된 프로필 이벤트 회원 ID 또는 버전");
		}
		if (candidates.applyEvent(event)) return;

		// 외부 호출은 매칭 DB 잠금을 놓은 뒤 한다. 실패하면 Kafka 재시도/DLT가 처리한다.
		MatchingProfileSnapshot snapshot = members.getMatchingSnapshot(event.memberId());
		if (snapshot == null || !event.memberId().equals(snapshot.memberId())) {
			throw new IllegalStateException("회원 서비스의 매칭 프로필 응답을 확인할 수 없습니다.");
		}
		switch (snapshot.state()) {
			case ACTIVE -> {
				var current = snapshot.profile();
				if (version != null && current.profileVersion() < version) {
					throw new IllegalStateException("회원 서비스 프로필 버전이 발행된 이벤트보다 낮습니다.");
				}
				candidates.applySnapshot(current);
			}
			case WITHDRAWN -> candidates.removeCandidate(snapshot.memberId(), snapshot.withdrawnAt());
			case NO_ACTIVE_PROFILE -> { /* 현재 매칭에 등록할 프로필이 없다. */ }
		}
	}
}
