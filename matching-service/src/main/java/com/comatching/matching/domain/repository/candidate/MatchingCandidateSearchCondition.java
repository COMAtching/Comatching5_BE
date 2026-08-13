package com.comatching.matching.domain.repository.candidate;

import java.util.List;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.matching.domain.enums.AgeOption;

public record MatchingCandidateSearchCondition(
		// --- 걸러내기(WHERE) ---
		Gender targetGender,
		String excludeMajor,
		List<Long> excludeMemberIds,
		Integer minAge,
		Integer maxAge,
		String requiredMbtiTraits,
		ContactFrequency requiredContactFrequency,
		HobbyCategory requiredHobbyCategory,

		// --- 점수 매기기(ORDER BY) ---
		Integer myAge,
		String scoreMbtiTraits,
		HobbyCategory scoreHobbyCategory,
		AgeOption scoreAgeOption,
		ContactFrequency scoreContactFrequency
) {
}
