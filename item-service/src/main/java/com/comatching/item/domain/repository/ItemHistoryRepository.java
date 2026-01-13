package com.comatching.item.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.entity.ItemHistory;
import com.comatching.item.domain.enums.ItemHistoryType;

@Repository
public interface ItemHistoryRepository extends JpaRepository<ItemHistory, Long> {

	Page<ItemHistory> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);

	@Query("SELECT h FROM ItemHistory h " +
		"WHERE h.memberId = :memberId " +
		"AND (:itemType IS NULL OR h.itemType = :itemType) " +
		"AND (:historyType IS NULL OR h.historyType = :historyType)")
	Page<ItemHistory> searchHistory(
		@Param("memberId") Long memberId,
		@Param("itemType") ItemType itemType,
		@Param("historyType") ItemHistoryType historyType,
		Pageable pageable
	);
}
