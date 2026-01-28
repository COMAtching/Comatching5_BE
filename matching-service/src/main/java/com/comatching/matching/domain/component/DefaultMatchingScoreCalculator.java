package com.comatching.matching.domain.component;

import java.util.List;

import org.springframework.stereotype.Component;

import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.enums.AgeOption;
import com.comatching.matching.domain.vo.Mbti;

@Component
public class DefaultMatchingScoreCalculator implements MatchingScoreCalculator {

	private static final int HOBBY_SCORE_HIGH = 20;
	private static final int HOBBY_SCORE_MEDIUM = 15;
	private static final int HOBBY_SCORE_LOW = 10;
	private static final int AGE_MATCH_SCORE = 20;
	private static final int CONTACT_MATCH_SCORE = 10;

	@Override
	public int calculate(MatchingCandidate candidate, MatchingRequest request, KoreanAge myAge) {
		int score = 0;

		score += candidate.getMbti().calculateScore(new Mbti(request.mbtiOption()));

		score += calculateHobbyScore(candidate.getHobbyCategories(), request.hobbyOption());

		score += calculateAgeScore(candidate.getAge(), myAge, request.ageOption());

		score += calculateContactScore(candidate, request);

		return score;
	}

	private int calculateHobbyScore(List<HobbyCategory> candidateHobbies, HobbyCategory targetCategory) {
		if (candidateHobbies == null || candidateHobbies.isEmpty() || targetCategory == null) {
			return 0;
		}

		if (!candidateHobbies.contains(targetCategory)) {
			return 0;
		}

		long matchCount = candidateHobbies.stream()
			.filter(category -> category == targetCategory)
			.count();

		if (matchCount >= 3) {
			return HOBBY_SCORE_HIGH;
		} else if (matchCount == 2) {
			return HOBBY_SCORE_MEDIUM;
		}
		return HOBBY_SCORE_LOW;
	}

	private int calculateAgeScore(KoreanAge candidateAge, KoreanAge myAge, AgeOption ageOption) {
		if (ageOption == null || candidateAge == null || myAge == null) {
			return 0;
		}

		boolean matches = switch (ageOption) {
			case EQUAL -> candidateAge.isEqual(myAge);
			case OLDER -> candidateAge.isOlderThan(myAge);
			case YOUNGER -> candidateAge.isYoungerThan(myAge);
		};

		return matches ? AGE_MATCH_SCORE : 0;
	}

	private int calculateContactScore(MatchingCandidate candidate, MatchingRequest request) {
		if (request.contactFrequency() != null && candidate.matchesContactFrequency(request.contactFrequency())) {
			return CONTACT_MATCH_SCORE;
		}
		return 0;
	}
}
