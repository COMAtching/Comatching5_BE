package com.comatching.matching.domain.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.enums.AgeOption;
import com.comatching.matching.domain.enums.ImportantOption;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateSearchCondition;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.global.exception.MatchingErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MatchingProcessor {

	private static final int MIN_ALLOWED_AGE = 20;
	private static final int MAX_ALLOWED_AGE = 27;
	private static final int MAX_CANDIDATE_FETCH_SIZE = 500;

	private final MatchingCandidateRepository candidateRepository;
	private final MatchingHistoryRepository historyRepository;
	private final MatchingScoreCalculator scoreCalculator;
	private final ImportantConditionCheckerFactory conditionCheckerFactory;

	public MatchingCandidate process(Long memberId, ProfileResponse myProfile, MatchingRequest request) {
		KoreanAge myAge = KoreanAge.fromBirthDate(myProfile.birthDate());
		List<Long> excludeMemberIds = historyRepository.findPartnerIdsByMemberId(memberId);
		MatchingCandidateSearchSeed searchSeed = createSearchSeed(myProfile, request, myAge, excludeMemberIds);
		List<MatchingCandidate> bestCandidates = new ArrayList<>();
		int maxScore = -1;
		Long lastMemberId = null;

		while (true) {
			List<MatchingCandidate> candidates = candidateRepository.findPotentialCandidates(searchSeed.toCondition(lastMemberId));
			if (candidates.isEmpty()) {
				break;
			}

			maxScore = filterAndScore(candidates, request, myAge, bestCandidates, maxScore);
			lastMemberId = candidates.get(candidates.size() - 1).getMemberId();

			if (candidates.size() < MAX_CANDIDATE_FETCH_SIZE) {
				break;
			}
		}

		if (bestCandidates.isEmpty()) {
			throw new BusinessException(MatchingErrorCode.NO_MATCHING_CANDIDATE);
		}

		return selectRandomCandidate(bestCandidates);
	}

	private MatchingCandidateSearchSeed createSearchSeed(
		ProfileResponse myProfile,
		MatchingRequest request,
		KoreanAge myAge,
		List<Long> excludeMemberIds
	) {
		String excludeMajor = request.sameMajorOption() ? myProfile.major() : null;
		Gender targetGender = (myProfile.gender() == Gender.MALE) ? Gender.FEMALE : Gender.MALE;
		return new MatchingCandidateSearchSeed(
			targetGender,
			excludeMajor,
			excludeMemberIds,
			minAge(request, myAge),
			maxAge(request, myAge),
			requiredMbtiTraits(request),
			requiredContactFrequency(request),
			requiredHobbyCategory(request),
			MAX_CANDIDATE_FETCH_SIZE
		);
	}

	private Integer minAge(MatchingRequest request, KoreanAge myAge) {
		Integer minAge = null;
		if (request.hasCompleteAgeLimit() && myAge != null) {
			minAge = Math.max(MIN_ALLOWED_AGE, myAge.getValue() + request.minAgeOffset());
		}
		if (request.importantOption() == ImportantOption.AGE && request.ageOption() == AgeOption.EQUAL && myAge != null) {
			minAge = max(minAge, myAge.getValue());
		}
		if (request.importantOption() == ImportantOption.AGE && request.ageOption() == AgeOption.OLDER && myAge != null) {
			minAge = max(minAge, myAge.getValue() + 1);
		}
		return minAge;
	}

	private Integer maxAge(MatchingRequest request, KoreanAge myAge) {
		Integer maxAge = null;
		if (request.hasCompleteAgeLimit() && myAge != null) {
			maxAge = Math.min(MAX_ALLOWED_AGE, myAge.getValue() + request.maxAgeOffset());
		}
		if (request.importantOption() == ImportantOption.AGE && request.ageOption() == AgeOption.EQUAL && myAge != null) {
			maxAge = min(maxAge, myAge.getValue());
		}
		if (request.importantOption() == ImportantOption.AGE && request.ageOption() == AgeOption.YOUNGER && myAge != null) {
			maxAge = min(maxAge, myAge.getValue() - 1);
		}
		return maxAge;
	}

	private Integer max(Integer current, int candidate) {
		return current == null ? candidate : Math.max(current, candidate);
	}

	private Integer min(Integer current, int candidate) {
		return current == null ? candidate : Math.min(current, candidate);
	}

	private String requiredMbtiTraits(MatchingRequest request) {
		if (request.importantOption() != ImportantOption.MBTI || request.mbtiOption() == null
			|| request.mbtiOption().isBlank()) {
			return null;
		}
		return request.mbtiOption().toUpperCase(Locale.ROOT);
	}

	private ContactFrequency requiredContactFrequency(MatchingRequest request) {
		return request.importantOption() == ImportantOption.CONTACT ? request.contactFrequency() : null;
	}

	private HobbyCategory requiredHobbyCategory(MatchingRequest request) {
		return request.importantOption() == ImportantOption.HOBBY ? request.hobbyOption() : null;
	}

	private int filterAndScore(
		List<MatchingCandidate> candidates,
		MatchingRequest request,
		KoreanAge myAge,
		List<MatchingCandidate> bestCandidates,
		int maxScore
	) {
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

		return maxScore;
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

	private record MatchingCandidateSearchSeed(
		Gender targetGender,
		String excludeMajor,
		List<Long> excludeMemberIds,
		Integer minAge,
		Integer maxAge,
		String requiredMbtiTraits,
		ContactFrequency requiredContactFrequency,
		HobbyCategory requiredHobbyCategory,
		int limit
	) {

		private MatchingCandidateSearchCondition toCondition(Long lastMemberId) {
			return new MatchingCandidateSearchCondition(
				targetGender,
				excludeMajor,
				excludeMemberIds,
				minAge,
				maxAge,
				requiredMbtiTraits,
				requiredContactFrequency,
				requiredHobbyCategory,
				lastMemberId,
				limit
			);
		}
	}
}
