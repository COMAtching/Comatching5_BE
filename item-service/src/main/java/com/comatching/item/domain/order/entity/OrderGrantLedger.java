package com.comatching.item.domain.order.entity;

import java.time.LocalDateTime;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "order_grant_ledger",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_order_item_grant_once", columnNames = {"orderId", "orderItemId"})
	},
	indexes = {
		@Index(name = "idx_grant_ledger_member", columnList = "memberId"),
		@Index(name = "idx_grant_ledger_order", columnList = "orderId")
	}
)
public class OrderGrantLedger extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long orderId;

	@Column(nullable = false)
	private Long orderItemId;

	@Column(nullable = false)
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemType itemType;

	@Column(nullable = false)
	private int quantity;

	@Column(nullable = false)
	private LocalDateTime grantedAt;

	@Builder
	public OrderGrantLedger(
		Long orderId,
		Long orderItemId,
		Long memberId,
		ItemType itemType,
		int quantity,
		LocalDateTime grantedAt
	) {
		this.orderId = orderId;
		this.orderItemId = orderItemId;
		this.memberId = memberId;
		this.itemType = itemType;
		this.quantity = quantity;
		this.grantedAt = grantedAt;
	}
}
