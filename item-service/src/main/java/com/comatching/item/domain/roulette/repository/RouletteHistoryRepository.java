package com.comatching.item.domain.roulette.repository;

import java.time.LocalDateTime;

import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.enums.RouletteType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouletteHistoryRepository extends JpaRepository<RouletteHistory, Long> {
	boolean existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
		Long memberId,
		RouletteType rouletteType,
		LocalDateTime startAt,
		LocalDateTime endAt
	);
}
