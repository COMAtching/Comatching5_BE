package com.comatching.item.domain.order.entity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.comatching.item.domain.order.enums.GrantStatus;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.enums.RefundStatus;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.global.exception.PaymentErrorCode;

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
				@Index(name = "idx_order_grant_work", columnList = "status, grant_status, id"),
				@Index(name = "idx_order_refund", columnList = "refund_status, id"),
				@Index(name = "idx_order_member_status", columnList = "memberId, status"),
				@Index(name = "idx_order_status_expires", columnList = "status, expiresAt"),
				@Index(name = "idx_order_member_product_status", columnList = "memberId, productCode, status")
		}
)
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long memberId;

	private Long productId;

	private String productCode;

	@Column(name = "product_quantity")
	private Integer productQuantity;

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

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "grant_status", nullable = false, length = 24)
	@ColumnDefault("'NOT_REQUESTED'")
	private GrantStatus grantStatus = GrantStatus.NOT_REQUESTED;

	@Column(name = "grant_attempt", nullable = false)
	@ColumnDefault("0")
	private int grantAttempt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "refund_status", nullable = false, length = 24)
	@ColumnDefault("'NONE'")
	private RefundStatus refundStatus = RefundStatus.NONE;

	private LocalDateTime refundStartedAt;
	private Long refundStartedBy;
	private LocalDateTime refundCompletedAt;
	private Long refundCompletedBy;

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
			Long productId,
			String productCode,
			Integer productQuantity,
			String requestedItemName,
			String requesterRealName,
			String requesterUsername,
			int requestedPrice,
			int expectedPrice,
			LocalDateTime requestedAt,
			LocalDateTime expiresAt
	) {
		this.memberId = memberId;
		this.productId = productId;
		this.productCode = productCode;
		this.productQuantity = productQuantity;
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

	public void completeGrant() {
		if (status != OrderStatus.APPROVED || grantStatus != GrantStatus.PENDING || refundStatus != RefundStatus.NONE) {
			throw new IllegalStateException("지급 대기 중인 승인 주문만 완료할 수 있습니다.");
		}
		grantStatus = GrantStatus.GRANTED;
	}

	public void startRefund(Long adminId, LocalDateTime now) {
		if (status != OrderStatus.APPROVED || grantStatus != GrantStatus.FAILED || refundStatus != RefundStatus.NONE) {
			throw new BusinessException(PaymentErrorCode.REFUND_NOT_STARTABLE);
		}
		refundStatus = RefundStatus.IN_PROGRESS;
		refundStartedAt = now.truncatedTo(ChronoUnit.MICROS);
		refundStartedBy = adminId;
	}

	public void completeRefund(Long adminId, LocalDateTime now) {
		if (status != OrderStatus.APPROVED || grantStatus != GrantStatus.FAILED || refundStatus != RefundStatus.IN_PROGRESS) {
			throw new BusinessException(PaymentErrorCode.REFUND_NOT_IN_PROGRESS);
		}
		refundStatus = RefundStatus.COMPLETED;
		refundCompletedAt = now.truncatedTo(ChronoUnit.MICROS);
		refundCompletedBy = adminId;
	}

	public void approve(LocalDateTime decidedAt) {
		this.status = OrderStatus.APPROVED;
		this.grantStatus = GrantStatus.PENDING;
		this.grantAttempt++;
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
