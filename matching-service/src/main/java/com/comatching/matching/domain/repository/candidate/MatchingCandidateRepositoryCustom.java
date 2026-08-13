package com.comatching.matching.domain.repository.candidate;

import com.comatching.matching.domain.entity.MatchingCandidate;

import java.util.Optional;

public interface MatchingCandidateRepositoryCustom {

	Optional<MatchingCandidate> findBestCandidate(MatchingCandidateSearchCondition condition);
}
