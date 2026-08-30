package com.comatching.item.domain.roulette.repository;

import java.util.List;
import java.util.Optional;

import com.comatching.item.domain.roulette.entity.RouletteReward;
import com.comatching.item.domain.roulette.enums.RouletteType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouletteRewardRepository extends JpaRepository<RouletteReward, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		SELECT rr
		FROM RouletteReward rr
		WHERE rr.rouletteType = :rouletteType
		AND rr.rangeStart <= :rouletteNumber
		AND rr.rangeEnd >= :rouletteNumber
		AND (rr.remainingCount IS NULL OR rr.remainingCount > 0)
		ORDER BY rr.id ASC
		""")
	Optional<RouletteReward> findAvailableByRouletteTypeAndRouletteNumber(
		@Param("rouletteType") RouletteType rouletteType,
		@Param("rouletteNumber") int rouletteNumber);
}
