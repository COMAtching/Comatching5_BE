package com.comatching.item.domain.item.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.item.entity.Item;

import jakarta.persistence.LockModeType;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT i FROM Item i " +
		"WHERE i.memberId = :memberId " +
		"AND i.itemType = :itemType " +
		"AND i.quantity > 0 " +
		"ORDER BY i.id ASC")
	List<Item> findAllUsableItems(
		@Param("memberId") Long memberId,
		@Param("itemType") ItemType itemType);

	@Query("SELECT i FROM Item i " +
		"WHERE i.memberId = :memberId " +
		"AND i.quantity > 0 " +
		"AND (:itemType IS NULL OR i.itemType = :itemType)")
	Page<Item> findMyUsableItems(
		@Param("memberId") Long memberId,
		@Param("itemType") ItemType itemType,
		Pageable pageable
	);

	@Query("SELECT COALESCE(SUM(i.quantity), 0) FROM Item i " +
		"WHERE i.memberId = :memberId " +
		"AND i.itemType = :itemType " +
		"AND i.quantity > 0")
	long sumUsableQuantityByMemberIdAndItemType(
		@Param("memberId") Long memberId,
		@Param("itemType") ItemType itemType
	);

	@Query("SELECT i.memberId AS memberId, i.itemType AS itemType, SUM(i.quantity) AS quantity " +
		"FROM Item i " +
		"WHERE i.memberId IN :memberIds " +
		"AND i.quantity > 0 " +
		"GROUP BY i.memberId, i.itemType")
	List<MemberItemQuantity> sumUsableQuantityByMemberIds(@Param("memberIds") List<Long> memberIds);

	interface MemberItemQuantity {
		Long getMemberId();

		ItemType getItemType();

		Long getQuantity();
	}
}
