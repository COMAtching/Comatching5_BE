package com.comatching.matching.domain.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.entity.WithdrawnMember;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
import com.comatching.matching.domain.repository.candidate.WithdrawnMemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class CandidateProfileStore {

	private final MatchingCandidateRepository candidateRepository;
	private final WithdrawnMemberRepository withdrawnMemberRepository;

	public void removeCandidate(Long memberId, LocalDateTime withdrawnAt) {
		// tombstone 을 후보 삭제보다 먼저, 그리고 saveAndFlush 로 즉시 INSERT 한다.
		// 이 INSERT 가 동시 upsert 의 갭 락과 만나는 직렬화 지점이다: upsert 가
		// 진행 중이면 여기서 그 커밋까지 대기한다.
		if (!withdrawnMemberRepository.existsById(memberId)) {
			withdrawnMemberRepository.saveAndFlush(WithdrawnMember.of(memberId, withdrawnAt));
		}

		// 후보 삭제는 반드시 잠금 조회(current read)로 한다. 스냅샷 읽기
		// (existsByMemberId 등)는 이 트랜잭션 시작 후 커밋된 행 — 방금 대기가
		// 풀리는 동안 동시 upsert 가 만든 후보 — 를 보지 못해 삭제를 건너뛰고,
		// 그 후보는 다시 지울 계기가 없어 탈퇴 회원이 영구히 매칭 대상으로 남는다.
		candidateRepository.findWithLockByMemberId(memberId)
			.ifPresent(candidateRepository::delete);
	}

	/** false이면 로컬 행만으로 안전하게 반영할 수 없어 회원 서비스의 스냅샷이 필요하다. */
	public boolean applyEvent(ProfileUpdatedMatchingEvent event) {
		if (hasTombstone(event.memberId())) return true;
		if (event.profileVersion() == null) return false;

		var candidate = candidateRepository.findWithLockByMemberId(event.memberId());
		if (candidate.isEmpty() || candidate.get().getLastProfileVersion() == 0) return false;
		candidate.get().applyCurrentProfile(event);
		return true;
	}

	public void applySnapshot(ProfileUpdatedMatchingEvent current) {
		// 원격 조회 중 탈퇴나 더 최신 갱신이 먼저 커밋됐을 수 있다.
		if (hasTombstone(current.memberId())) return;
		candidateRepository.findWithLockByMemberId(current.memberId()).ifPresentOrElse(
			candidate -> candidate.applyCurrentProfile(current),
			() -> candidateRepository.save(MatchingCandidate.fromProfile(current))
		);
	}

	private boolean hasTombstone(Long memberId) {
		return withdrawnMemberRepository.findWithLockByMemberId(memberId).isPresent();
	}
}
