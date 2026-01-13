package com.comatching.item.domain.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.comatching.item.domain.entity.Item;
import com.comatching.common.domain.enums.ItemType;

import jakarta.persistence.LockModeType;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT i FROM Item i " +
		"WHERE i.memberId = :memberId " +
		"AND i.itemType = :itemType " +
		"AND i.quantity > 0 " +
		"AND i.expiredAt > CURRENT_TIMESTAMP " +
		"ORDER BY i.expiredAt ASC")
	List<Item> findAllUsableItems(
		@Param("memberId") Long memberId,
		@Param("itemType") ItemType itemType);

	@Query("SELECT i FROM Item i " +
		"WHERE i.memberId = :memberId " +
		"AND i.quantity > 0 " +
		"AND i.expiredAt > CURRENT_TIMESTAMP " +
		"AND (:itemType IS NULL OR i.itemType = :itemType)")
	Page<Item> findMyUsableItems(
		@Param("memberId") Long memberId,
		@Param("itemType") ItemType itemType,
		Pageable pageable
	);
}
