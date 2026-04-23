package com.comatching.item.domain.product.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

import com.comatching.item.domain.product.enums.PurchaseStatus;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "purchase_request")
public class PurchaseRequest {

	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long memberId;

	private Long productId; // 어떤 상품을 샀는지 기록

	private String productName; // 상품명이 바뀌어도 기록 남기기용

	private int paymentPrice; // 구매 요청 당시의 가격을 저장 (가격 변동 대비)

	@Enumerated(EnumType.STRING)
	private PurchaseStatus status;

	private LocalDateTime requestedAt;
	private LocalDateTime approvedAt;

	@Builder
	public PurchaseRequest(Long memberId, Long productId, String productName, int paymentPrice) {
		this.memberId = memberId;
		this.productId = productId;
		this.productName = productName;
		this.paymentPrice = paymentPrice;
		this.status = PurchaseStatus.PENDING;
		this.requestedAt = LocalDateTime.now();
	}

	public void approve() {
		this.status = PurchaseStatus.APPROVED;
		this.approvedAt = LocalDateTime.now();
	}

	public void reject() {
		this.status = PurchaseStatus.REJECTED;
	}
}