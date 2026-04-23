package com.comatching.matching.domain.component;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.global.exception.MatchingErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MatchingProcessor {

	private static final int MIN_ALLOWED_AGE = 20;
	private static final int MAX_ALLOWED_AGE = 27;

	private final MatchingCandidateRepository candidateRepository;
	private final MatchingHistoryRepository historyRepository;
	private final MatchingScoreCalculator scoreCalculator;
	private final ImportantConditionCheckerFactory conditionCheckerFactory;

	public MatchingCandidate process(Long memberId, ProfileResponse myProfile, MatchingRequest request) {
		List<MatchingCandidate> candidates = findCandidates(memberId, myProfile, request);
		KoreanAge myAge = KoreanAge.fromBirthDate(myProfile.birthDate());

		List<MatchingCandidate> bestCandidates = filterAndScore(candidates, request, myAge);

		if (bestCandidates.isEmpty()) {
			throw new BusinessException(MatchingErrorCode.NO_MATCHING_CANDIDATE);
		}

		return selectRandomCandidate(bestCandidates);
	}

	private List<MatchingCandidate> findCandidates(Long memberId, ProfileResponse myProfile, MatchingRequest request) {
		List<Long> excludeMemberIds = historyRepository.findPartnerIdsByMemberId(memberId);
		String excludeMajor = request.sameMajorOption() ? myProfile.major() : null;
		Gender targetGender = (myProfile.gender() == Gender.MALE) ? Gender.FEMALE : Gender.MALE;

		return candidateRepository.findPotentialCandidates(targetGender, excludeMajor, excludeMemberIds);
	}

	private List<MatchingCandidate> filterAndScore(List<MatchingCandidate> candidates,
		MatchingRequest request, KoreanAge myAge) {

		List<MatchingCandidate> bestCandidates = new ArrayList<>();
		int maxScore = -1;

		for (MatchingCandidate candidate : candidates) {
			if (!matchesAgeLimit(candidate, request, myAge)) {
				continue;
			}

			if (!conditionCheckerFactory.check(request.importantOption(), candidate, request, myAge)) {
				continue;
			}

			int score = scoreCalculator.calculate(candidate, request, myAge);

			if (score > maxScore) {
				maxScore = score;
				bestCandidates.clear();
				bestCandidates.add(candidate);
			} else if (score == maxScore) {
				bestCandidates.add(candidate);
			}
		}

		return bestCandidates;
	}

	private boolean matchesAgeLimit(MatchingCandidate candidate, MatchingRequest request, KoreanAge myAge) {
		if (!request.hasAgeLimit()) {
			return true;
		}

		if (!request.hasCompleteAgeLimit() || candidate.getAge() == null || myAge == null) {
			return false;
		}

		int minAge = Math.max(MIN_ALLOWED_AGE, myAge.getValue() + request.minAgeOffset());
		int maxAge = Math.min(MAX_ALLOWED_AGE, myAge.getValue() + request.maxAgeOffset());

		if (minAge > maxAge) {
			return false;
		}

		int candidateAge = candidate.getAge().getValue();
		return candidateAge >= minAge && candidateAge <= maxAge;
	}

	private MatchingCandidate selectRandomCandidate(List<MatchingCandidate> candidates) {
		int randomIndex = (int) (Math.random() * candidates.size());
		return candidates.get(randomIndex);
	}
}
