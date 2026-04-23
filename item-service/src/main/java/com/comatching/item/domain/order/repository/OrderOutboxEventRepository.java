package com.comatching.item.domain.order.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.comatching.item.domain.order.entity.OrderOutboxEvent;
import com.comatching.item.domain.order.enums.OrderOutboxStatus;

public interface OrderOutboxEventRepository extends JpaRepository<OrderOutboxEvent, Long> {

	List<OrderOutboxEvent> findTop100ByStatusOrderByIdAsc(OrderOutboxStatus status);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		UPDATE OrderOutboxEvent e
		SET e.status = com.comatching.item.domain.order.enums.OrderOutboxStatus.PROCESSING,
			e.processingStartedAt = :processingStartedAt
		WHERE e.id = :eventId
		AND e.status = com.comatching.item.domain.order.enums.OrderOutboxStatus.NEW
		""")
	int markProcessingIfNew(@Param("eventId") Long eventId, @Param("processingStartedAt") LocalDateTime processingStartedAt);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		UPDATE OrderOutboxEvent e
		SET e.status = com.comatching.item.domain.order.enums.OrderOutboxStatus.PUBLISHED,
			e.publishedAt = :publishedAt
		WHERE e.id = :eventId
		AND e.status = com.comatching.item.domain.order.enums.OrderOutboxStatus.PROCESSING
		""")
	int markPublished(@Param("eventId") Long eventId, @Param("publishedAt") LocalDateTime publishedAt);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		UPDATE OrderOutboxEvent e
		SET e.status = com.comatching.item.domain.order.enums.OrderOutboxStatus.NEW,
			e.processingStartedAt = null,
			e.retryCount = e.retryCount + 1,
			e.lastError = :lastError
		WHERE e.id = :eventId
		AND e.status = com.comatching.item.domain.order.enums.OrderOutboxStatus.PROCESSING
		""")
	int markRetry(@Param("eventId") Long eventId, @Param("lastError") String lastError);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		UPDATE OrderOutboxEvent e
		SET e.status = com.comatching.item.domain.order.enums.OrderOutboxStatus.NEW,
			e.processingStartedAt = null
		WHERE e.status = com.comatching.item.domain.order.enums.OrderOutboxStatus.PROCESSING
		AND e.processingStartedAt <= :staleThreshold
		""")
	int resetStaleProcessing(@Param("staleThreshold") LocalDateTime staleThreshold);
}
