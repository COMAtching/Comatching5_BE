package com.comatching.item.domain.order.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.enums.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

	@Query("""
		SELECT CASE WHEN COUNT(o) > 0 THEN true ELSE false END
		FROM Order o
		WHERE o.memberId = :memberId
		AND o.status = com.comatching.item.domain.order.enums.OrderStatus.PENDING
		AND o.expiresAt > :now
		""")
	boolean existsActivePendingOrder(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

	List<Order> findAllByStatusOrderByRequestedAtDesc(OrderStatus status);

	Page<Order> findAllByStatusAndExpiresAtAfter(OrderStatus status, LocalDateTime now, Pageable pageable);

	List<Order> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(OrderStatus status, LocalDateTime now);

	@Query("SELECT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.id = :orderId")
	Optional<Order> findByIdWithItems(@Param("orderId") Long orderId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		UPDATE Order o
		SET o.status = :targetStatus,
			o.decidedAt = :decidedAt
		WHERE o.id = :orderId
		AND o.status = :currentStatus
		AND o.expiresAt > :now
		""")
	int updateStatusIfCurrentAndNotExpired(
		@Param("orderId") Long orderId,
		@Param("currentStatus") OrderStatus currentStatus,
		@Param("targetStatus") OrderStatus targetStatus,
		@Param("decidedAt") LocalDateTime decidedAt,
		@Param("now") LocalDateTime now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		UPDATE Order o
		SET o.status = com.comatching.item.domain.order.enums.OrderStatus.EXPIRED,
			o.decidedAt = :now
		WHERE o.id = :orderId
		AND o.status = :currentStatus
		AND o.expiresAt <= :now
		""")
	int updateStatusToExpiredIfCurrent(
		@Param("orderId") Long orderId,
		@Param("now") LocalDateTime now,
		@Param("currentStatus") OrderStatus currentStatus
	);
}
