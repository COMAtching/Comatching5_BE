package com.comatching.matching.domain.repository.candidate;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.comatching.matching.domain.entity.MatchingCandidate;

@Repository
public interface MatchingCandidateRepository extends JpaRepository<MatchingCandidate, Long>, MatchingCandidateRepositoryCustom {

	void deleteByMemberId(Long memberId);
	boolean existsByMemberId(Long memberId);
}
