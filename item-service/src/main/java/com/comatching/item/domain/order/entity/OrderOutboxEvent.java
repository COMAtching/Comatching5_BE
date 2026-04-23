package com.comatching.item.domain.order.entity;

import java.time.LocalDateTime;

import com.comatching.common.entity.BaseEntity;
import com.comatching.item.domain.order.enums.OrderOutboxStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "order_outbox_events",
	indexes = {
		@Index(name = "idx_order_outbox_status", columnList = "status, id")
	}
)
public class OrderOutboxEvent extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String aggregateType;

	@Column(nullable = false)
	private Long aggregateId;

	@Column(nullable = false)
	private String eventType;

	@Lob
	@Column(nullable = false, columnDefinition = "TEXT")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderOutboxStatus status;

	@Column(nullable = false)
	private int retryCount;

	@Column(length = 500)
	private String lastError;

	@Column(nullable = false)
	private LocalDateTime occurredAt;

	private LocalDateTime processingStartedAt;

	private LocalDateTime publishedAt;

	@Builder
	public OrderOutboxEvent(
		String aggregateType,
		Long aggregateId,
		String eventType,
		String payload,
		LocalDateTime occurredAt
	) {
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.occurredAt = occurredAt;
		this.status = OrderOutboxStatus.NEW;
		this.retryCount = 0;
	}
}
