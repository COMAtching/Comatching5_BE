package com.comatching.matching.domain.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateServiceImpl implements CandidateService {

	private final MatchingCandidateRepository candidateRepository;

	@Override
	@Transactional
	public void removeCandidate(Long memberId) {

		if (candidateRepository.existsByMemberId(memberId)) {
			candidateRepository.deleteByMemberId(memberId);
		}
	}

	@Override
	@Transactional
	public void upsertCandidate(ProfileUpdatedMatchingEvent event) {
		candidateRepository.findById(event.memberId())
			.ifPresentOrElse(
				candidate -> {
					candidate.syncProfile(
						event.profileId(),
						event.gender(),
						event.mbti(),
						event.major(),
						event.contactFrequency(),
						event.hobbies(),
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
						event.hobbies(),
						event.birthDate(),
						event.isMatchable()
					);
					candidateRepository.save(newCandidate);
				}
			);
	}
}