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
		// tombstone 을 후보 삭제보다 먼저 넣는다. 반대 순서면 "후보는 지워졌는데
		// tombstone 은 아직"인 커밋 사이 상태에서 늦은 갱신이 끼어들 수 있다.
		if (!withdrawnMemberRepository.existsById(memberId)) {
			withdrawnMemberRepository.save(WithdrawnMember.of(memberId, withdrawnAt));
		}

		if (candidateRepository.existsByMemberId(memberId)) {
			candidateRepository.deleteByMemberId(memberId);
		}
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
