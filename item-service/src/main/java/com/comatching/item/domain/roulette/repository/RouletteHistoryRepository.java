package com.comatching.item.domain.roulette.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.enums.RewardType;
import com.comatching.item.domain.roulette.enums.RouletteType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RouletteHistoryRepository extends JpaRepository<RouletteHistory, Long> {
	boolean existsByMemberIdAndRouletteTypeAndParticipationDate(
		Long memberId,
		RouletteType rouletteType,
		LocalDate participationDate
	);

	@EntityGraph(attributePaths = "reward")
	// 상품권 당첨 이력 중 지급되지 않은 건만 당첨 시각 역순으로 조회한다.
	List<RouletteHistory> findAllByReward_RewardTypeAndRewardGrantedFalseOrderByParticipatedAtDesc(
		RewardType rewardType
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	// 두 관리자가 같은 당첨 건을 동시에 지급 완료 처리하지 못하도록 이력 행을 잠근다.
	@Query("""
		SELECT rh
		FROM RouletteHistory rh
		JOIN FETCH rh.reward
		WHERE rh.id = :historyId
		""")
	Optional<RouletteHistory> findByIdWithRewardForUpdate(@Param("historyId") Long historyId);
}
