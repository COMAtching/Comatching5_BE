package com.comatching.item.domain.product.service;

import java.time.LocalDateTime;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.order.dto.FailedGrantResponse;
import com.comatching.item.domain.order.dto.OrderGrantResponse;
import com.comatching.item.domain.order.dto.OrderRefundResponse;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.enums.GrantStatus;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.enums.RefundStatus;
import com.comatching.item.domain.order.outbox.OrderGrantOutboxRepository;
import com.comatching.item.domain.order.repository.OrderGrantLedgerRepository;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.order.service.OrderDecisionLogService;
import com.comatching.item.domain.order.service.OrderOutboxService;
import com.comatching.item.domain.product.dto.PurchaseRequestDto;
import com.comatching.item.global.exception.PaymentErrorCode;

import com.comatching.item.infra.bankdeposit.BankApprovalGate;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminPaymentServiceImpl implements AdminPaymentService {
	@Autowired(required = false)
	private BankApprovalGate depositGate;


	private static final String EXPIRE_REASON = "AUTO_EXPIRED_BEFORE_ADMIN_DECISION";
	private static final int MAX_PAGE_SIZE = 100;
	private static final Sort DEFAULT_PENDING_REQUEST_SORT = Sort.by(Sort.Direction.DESC, "requestedAt");

	private final OrderRepository orderRepository;
	private final OrderDecisionLogService orderDecisionLogService;
	private final OrderOutboxService orderOutboxService;
	private final OrderGrantLedgerRepository grantLedger;
	private final OrderGrantOutboxRepository grantOutbox;

	@Override
	@Transactional(readOnly = true)
	public OrderGrantResponse getGrant(Long orderId) {
		var order = orderRepository.findById(orderId).orElseThrow(() -> new BusinessException(PaymentErrorCode.REQUEST_NOT_FOUND));
		return new OrderGrantResponse(orderId, order.getStatus(), order.getGrantStatus(), order.getGrantAttempt(),
				order.getRefundStatus());
	}

	@Override
	@Transactional(readOnly = true)
	public PagingResponse<FailedGrantResponse> getFailedGrants(int page, int size) {
		return PagingResponse.from(orderRepository.findFailedGrants(adminPage(page, size)));
	}

	private Pageable adminPage(int page, int size) {
		int pageSize = Math.min(size, MAX_PAGE_SIZE);
		if (page < 0 || size < 1 || (long) page * pageSize > Integer.MAX_VALUE) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "조회 가능한 페이지 범위를 확인해주세요.");
		}
		return PageRequest.of(page, pageSize);
	}

	@Override
	public OrderRefundResponse startRefund(Long orderId, Long adminId) {
		Order order = lockOrder(orderId);
		order.startRefund(adminId, LocalDateTime.now());
		verifyNoGrantEvidence(orderId);
		return OrderRefundResponse.from(order);
	}

	@Override
	public OrderRefundResponse completeRefund(Long orderId, Long adminId) {
		Order order = lockOrder(orderId);
		if (order.getRefundStatus() != RefundStatus.COMPLETED) {
			order.completeRefund(adminId, LocalDateTime.now());
			verifyNoGrantEvidence(orderId);
			grantOutbox.enqueueRefund(order);
		}
		return OrderRefundResponse.from(order);
	}

	private Order lockOrder(Long orderId) {
		return orderRepository.findByIdForUpdate(orderId)
				.orElseThrow(() -> new BusinessException(PaymentErrorCode.REQUEST_NOT_FOUND));
	}

	private void verifyNoGrantEvidence(Long orderId) {
		if (grantLedger.existsByOrderId(orderId) || grantOutbox.hasGrantEvent(orderId)) {
			throw new BusinessException(PaymentErrorCode.GRANT_REVIEW_REQUIRED);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public PagingResponse<OrderRefundResponse> getRefunds(RefundStatus status, int page, int size) {
		if (status == RefundStatus.NONE) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "진행 중이거나 완료된 환불만 조회할 수 있습니다.");
		}
		return PagingResponse.from(orderRepository.findByRefundStatusOrderByIdDesc(status, adminPage(page, size))
				.map(OrderRefundResponse::from));
	}

	@Override
	public void retryGrant(Long orderId) {
		if (orderRepository.retryFailedGrant(orderId) != 1) {
			throw new BusinessException(PaymentErrorCode.GRANT_NOT_RETRYABLE);
		}
	}

	@Override
	public PagingResponse<PurchaseRequestDto> getPendingRequests(Pageable pageable) {
		Pageable boundedPageable = toBoundedPageable(pageable);
		return PagingResponse.from(
			orderRepository.findAllByStatusAndExpiresAtAfter(
				OrderStatus.PENDING,
				LocalDateTime.now(),
				boundedPageable
			).map(PurchaseRequestDto::from)
		);
	}

	private Pageable toBoundedPageable(Pageable pageable) {
		if (pageable == null || pageable.isUnpaged()) {
			return PageRequest.of(0, MAX_PAGE_SIZE, DEFAULT_PENDING_REQUEST_SORT);
		}

		int pageSize = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
		Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : DEFAULT_PENDING_REQUEST_SORT;
		return PageRequest.of(pageable.getPageNumber(), pageSize, sort);
	}

	@Override
	public void approvePurchase(Long requestId, Long adminId) {
		if (depositGate != null) depositGate.requireUnlinkedApprovalAllowed(requestId);
		approveMatchedPurchase(requestId, adminId, null);
	}

	@Override
	public void approveMatchedPurchase(Long requestId, Long adminId, String reason) {
		LocalDateTime now = LocalDateTime.now();
		Order order = findOrderWithItems(requestId);

		if (transitionToExpiredIfNeeded(order, now)) {
			throw new BusinessException(PaymentErrorCode.REQUEST_EXPIRED);
		}

		int updatedCount = orderRepository.approveAndQueue(requestId, now);
		if (updatedCount == 0) {
			throw resolveNotProcessableRequest(requestId);
		}

		orderDecisionLogService.logDecision(
			order.getId(),
			OrderStatus.PENDING,
			OrderStatus.APPROVED,
			adminId,
			reason,
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
				if (order.getGrantStatus() == GrantStatus.REVIEW_REQUIRED) {
					return new BusinessException(PaymentErrorCode.GRANT_REVIEW_REQUIRED);
				}
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
