package com.comatching.item.domain.order.entity;

import java.time.LocalDateTime;

import com.comatching.common.entity.BaseEntity;
import com.comatching.item.domain.order.enums.OrderStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "order_decision_logs",
	indexes = {
		@Index(name = "idx_order_decision_order", columnList = "orderId")
	}
)
public class OrderDecisionLog extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long orderId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus toStatus;

	private Long decidedByAdminId;

	@Column(length = 200)
	private String reason;

	@Column(nullable = false)
	private LocalDateTime decidedAt;

	@Builder
	public OrderDecisionLog(
		Long orderId,
		OrderStatus fromStatus,
		OrderStatus toStatus,
		Long decidedByAdminId,
		String reason,
		LocalDateTime decidedAt
	) {
		this.orderId = orderId;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.decidedByAdminId = decidedByAdminId;
		this.reason = reason;
		this.decidedAt = decidedAt;
	}
}
