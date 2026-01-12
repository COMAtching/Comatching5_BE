package com.comatching.matching.domain.repository.history;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.comatching.matching.domain.entity.MatchingHistory;

@Repository
public interface MatchingHistoryRepository extends JpaRepository<MatchingHistory, Long> {

	@Query("SELECT m.partnerId FROM MatchingHistory m WHERE m.memberId = :memberId")
	List<Long> findPartnerIdsByMemberId(@Param("memberId") Long memberId);

	Page<MatchingHistory> findByMemberIdOrderByMatchedAtDesc(Long memberId, Pageable pageable);
}
