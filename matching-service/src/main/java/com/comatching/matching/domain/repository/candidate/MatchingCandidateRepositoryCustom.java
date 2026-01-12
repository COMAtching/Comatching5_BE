package com.comatching.matching.domain.repository.candidate;

import java.util.List;
import java.util.Optional;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.enums.AgeOption;

public interface MatchingCandidateRepositoryCustom {

	List<MatchingCandidate> findPotentialCandidates(
		Gender targetGender,
		String excludeMajor,
		List<Long> excludeMemberIds,
		long limitCount
	);
}
