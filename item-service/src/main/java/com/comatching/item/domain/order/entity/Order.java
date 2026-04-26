package com.comatching.item.domain.order.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import com.comatching.item.domain.order.enums.OrderStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "purchase_order",
	indexes = {
		@Index(name = "idx_order_member_status", columnList = "memberId, status"),
		@Index(name = "idx_order_status_expires", columnList = "status, expiresAt")
	}
)
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long memberId;

	@Column(nullable = false)
	private String requestedItemName;

	@Column(nullable = false)
	private String requesterRealName;

	@Column(nullable = false)
	private String requesterUsername;

	@Column(nullable = false)
	private int requestedPrice;

	@Column(nullable = false)
	private int expectedPrice;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status;

	@Column(nullable = false)
	private LocalDateTime requestedAt;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	private LocalDateTime decidedAt;

	@BatchSize(size = 100)
	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderItem> orderItems = new ArrayList<>();

	@Builder
	public Order(
		Long memberId,
		String requestedItemName,
		String requesterRealName,
		String requesterUsername,
		int requestedPrice,
		int expectedPrice,
		LocalDateTime requestedAt,
		LocalDateTime expiresAt
	) {
		this.memberId = memberId;
		this.requestedItemName = requestedItemName;
		this.requesterRealName = requesterRealName;
		this.requesterUsername = requesterUsername;
		this.requestedPrice = requestedPrice;
		this.expectedPrice = expectedPrice;
		this.status = OrderStatus.PENDING;
		this.requestedAt = requestedAt;
		this.expiresAt = expiresAt;
	}

	public void addOrderItem(OrderItem orderItem) {
		this.orderItems.add(orderItem);
		orderItem.assignOrder(this);
	}

	public void approve(LocalDateTime decidedAt) {
		this.status = OrderStatus.APPROVED;
		this.decidedAt = decidedAt;
	}

	public void reject(LocalDateTime decidedAt) {
		this.status = OrderStatus.REJECTED;
		this.decidedAt = decidedAt;
	}

	public void expire(LocalDateTime decidedAt) {
		this.status = OrderStatus.EXPIRED;
		this.decidedAt = decidedAt;
	}
}
