package com.comatching.matching.domain.repository.candidate;

import java.util.List;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;

public record MatchingCandidateSearchCondition(
	Gender targetGender,
	String excludeMajor,
	List<Long> excludeMemberIds,
	Integer minAge,
	Integer maxAge,
	String requiredMbtiTraits,
	ContactFrequency requiredContactFrequency,
	HobbyCategory requiredHobbyCategory,
	Long lastMemberIdExclusive,
	int limit
) {
}
