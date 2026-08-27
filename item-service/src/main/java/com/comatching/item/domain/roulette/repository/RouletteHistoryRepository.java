package com.comatching.item.domain.roulette.repository;

import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.enums.RouletteType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouletteHistoryRepository extends JpaRepository<RouletteHistory, Long> {
	@Query("""
		SELECT CASE WHEN COUNT(rh) > 0 THEN true ELSE false END
		FROM RouletteHistory rh
		WHERE rh.memberId = :memberId
		AND rh.rouletteType = :rouletteType
		AND rh.participatedAt >= CURRENT_DATE
		""")
	boolean existsTodayByMemberIdAndRouletteType(
		@Param("memberId") Long memberId,
		@Param("rouletteType") RouletteType rouletteType);
}
