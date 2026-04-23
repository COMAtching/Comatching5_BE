package com.comatching.item.domain.product.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.item.service.ItemService;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.entity.OrderItem;
import com.comatching.item.domain.order.entity.OrderGrantLedger;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.repository.OrderGrantLedgerRepository;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.order.service.OrderDecisionLogService;
import com.comatching.item.domain.order.service.OrderOutboxService;
import com.comatching.item.domain.product.dto.PurchaseRequestDto;
import com.comatching.item.global.exception.PaymentErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminPaymentServiceImpl implements AdminPaymentService {

	private static final String EXPIRE_REASON = "AUTO_EXPIRED_BEFORE_ADMIN_DECISION";

	private final OrderRepository orderRepository;
	private final OrderGrantLedgerRepository orderGrantLedgerRepository;
	private final OrderDecisionLogService orderDecisionLogService;
	private final OrderOutboxService orderOutboxService;
	private final ItemService itemService;

	// 상수: 구매 아이템의 유효기간 (약 100년)
	private static final int PURCHASED_ITEM_EXPIRE_DAYS = 36500;

	@Override
	public List<PurchaseRequestDto> getPendingRequests() {
		return orderRepository.findAllByStatusAndExpiresAtAfterOrderByRequestedAtDesc(OrderStatus.PENDING, LocalDateTime.now())
			.stream()
			.map(PurchaseRequestDto::from)
			.toList();
	}

	@Override
	public void approvePurchase(Long requestId, Long adminId) {
		LocalDateTime now = LocalDateTime.now();
		Order order = findOrderWithItems(requestId);

		if (transitionToExpiredIfNeeded(order, now)) {
			throw new BusinessException(PaymentErrorCode.REQUEST_EXPIRED);
		}

		int updatedCount = orderRepository.updateStatusIfCurrentAndNotExpired(
			requestId,
			OrderStatus.PENDING,
			OrderStatus.APPROVED,
			now,
			now
		);
		if (updatedCount == 0) {
			throw resolveNotProcessableRequest(requestId);
		}

		for (OrderItem reward : order.getOrderItems()) {
			if (orderGrantLedgerRepository.existsByOrderIdAndOrderItemId(order.getId(), reward.getId())) {
				continue;
			}

			OrderGrantLedger ledger = OrderGrantLedger.builder()
				.orderId(order.getId())
				.orderItemId(reward.getId())
				.memberId(order.getMemberId())
				.itemType(reward.getItemType())
				.quantity(reward.getQuantity())
				.grantedAt(now)
				.build();
			orderGrantLedgerRepository.save(ledger);

			AddItemRequest addItemRequest = new AddItemRequest(
				reward.getItemType(),
				reward.getQuantity(),
				ItemRoute.CHARGE,
				PURCHASED_ITEM_EXPIRE_DAYS
			);

			itemService.addItem(order.getMemberId(), addItemRequest);
		}

		orderDecisionLogService.logDecision(
			order.getId(),
			OrderStatus.PENDING,
			OrderStatus.APPROVED,
			adminId,
			null,
			now
		);
		orderOutboxService.enqueueOrderStatusChanged(
			order.getId(),
			OrderStatus.PENDING,
			OrderStatus.APPROVED,
			now,
			adminId,
			null
		);
	}

	@Override
	public void rejectPurchase(Long requestId, Long adminId) {
		LocalDateTime now = LocalDateTime.now();
		Order order = findOrderWithItems(requestId);

		if (transitionToExpiredIfNeeded(order, now)) {
			throw new BusinessException(PaymentErrorCode.REQUEST_EXPIRED);
		}

		int updatedCount = orderRepository.updateStatusIfCurrentAndNotExpired(
			requestId,
			OrderStatus.PENDING,
			OrderStatus.REJECTED,
			now,
			now
		);
		if (updatedCount == 0) {
			throw resolveNotProcessableRequest(requestId);
		}

		orderDecisionLogService.logDecision(
			order.getId(),
			OrderStatus.PENDING,
			OrderStatus.REJECTED,
			adminId,
			null,
			now
		);
		orderOutboxService.enqueueOrderStatusChanged(
			order.getId(),
			OrderStatus.PENDING,
			OrderStatus.REJECTED,
			now,
			adminId,
			null
		);
	}

	private BusinessException resolveNotProcessableRequest(Long requestId) {
		LocalDateTime now = LocalDateTime.now();
		return orderRepository.findById(requestId)
			.map(order -> {
				boolean expired = order.getStatus() == OrderStatus.EXPIRED
					|| (order.getStatus() == OrderStatus.PENDING && !order.getExpiresAt().isAfter(now));
				if (expired) {
					orderRepository.updateStatusToExpiredIfCurrent(order.getId(), now, OrderStatus.PENDING);
					return new BusinessException(PaymentErrorCode.REQUEST_EXPIRED);
				}
				return new BusinessException(PaymentErrorCode.ALREADY_PROCESSED);
			})
			.orElseGet(() -> new BusinessException(PaymentErrorCode.REQUEST_NOT_FOUND));
	}

	private Order findOrderWithItems(Long requestId) {
		return orderRepository.findByIdWithItems(requestId)
			.orElseThrow(() -> new BusinessException(PaymentErrorCode.REQUEST_NOT_FOUND));
	}

	private boolean transitionToExpiredIfNeeded(Order order, LocalDateTime now) {
		if (order.getStatus() != OrderStatus.PENDING) {
			return order.getStatus() == OrderStatus.EXPIRED;
		}

		if (order.getExpiresAt().isAfter(now)) {
			return false;
		}

		int expired = orderRepository.updateStatusToExpiredIfCurrent(order.getId(), now, OrderStatus.PENDING);
		if (expired == 1) {
			orderDecisionLogService.logDecision(
				order.getId(),
				OrderStatus.PENDING,
				OrderStatus.EXPIRED,
				null,
				EXPIRE_REASON,
				now
			);
			orderOutboxService.enqueueOrderStatusChanged(
				order.getId(),
				OrderStatus.PENDING,
				OrderStatus.EXPIRED,
				now,
				null,
				EXPIRE_REASON
			);
		}
		return true;
	}
}
