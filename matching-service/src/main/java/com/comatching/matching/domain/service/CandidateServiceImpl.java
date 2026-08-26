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
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateServiceImpl implements CandidateService {

	private final MatchingCandidateRepository candidateRepository;
	private final WithdrawnMemberRepository withdrawnMemberRepository;

	@Override
	@Transactional
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

	@Override
	@Transactional
	public void upsertCandidate(ProfileUpdatedMatchingEvent event) {
		// 탈퇴한 회원의 갱신 이벤트가 늦게 도착한 경우. 여기서 걸러내지 않으면
		// 탈퇴로 지워진 후보가 upsert 로 부활한다. 잠금 조회인 이유는
		// WithdrawnMemberRepository 주석 참고.
		if (withdrawnMemberRepository.findWithLockByMemberId(event.memberId()).isPresent()) {
			log.info("탈퇴 회원의 프로필 갱신 이벤트를 무시한다. memberId={}", event.memberId());
			return;
		}

		candidateRepository.findById(event.memberId())
			.ifPresentOrElse(
				candidate -> {
					candidate.syncProfile(
						event.profileId(),
						event.gender(),
						event.mbti(),
						event.major(),
						event.contactFrequency(),
						event.hobbyCategories(),
						event.birthDate(),
						event.isMatchable()
					);
				},
				() -> {
					MatchingCandidate newCandidate = MatchingCandidate.create(
						event.memberId(),
						event.profileId(),
						event.gender(),
						event.mbti(),
						event.major(),
						event.contactFrequency(),
						event.hobbyCategories(),
						event.birthDate(),
						event.isMatchable()
					);
					candidateRepository.save(newCandidate);
				}
			);
	}
}
