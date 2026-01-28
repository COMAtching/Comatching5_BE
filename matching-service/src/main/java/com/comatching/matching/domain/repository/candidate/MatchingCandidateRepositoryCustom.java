package com.comatching.matching.domain.repository.candidate;

import java.util.List;

import com.comatching.common.domain.enums.Gender;
import com.comatching.matching.domain.entity.MatchingCandidate;

public interface MatchingCandidateRepositoryCustom {

	List<MatchingCandidate> findPotentialCandidates(
		Gender targetGender,
		String excludeMajor,
		List<Long> excludeMemberIds
	);
}
