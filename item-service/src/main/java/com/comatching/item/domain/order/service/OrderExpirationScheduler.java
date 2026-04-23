package com.comatching.item.domain.order.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirationScheduler {

	private static final String EXPIRE_REASON = "AUTO_EXPIRED";
	private static final int MAX_BATCH_LOOP = 10;

	private final OrderRepository orderRepository;
	private final OrderDecisionLogService orderDecisionLogService;
	private final OrderOutboxService orderOutboxService;

	@Scheduled(fixedDelayString = "${comatching.order.expire-check-ms:30000}")
	@Transactional
	public void expireOverdueOrders() {
		int expiredCount = 0;
		LocalDateTime now = LocalDateTime.now();

		for (int i = 0; i < MAX_BATCH_LOOP; i++) {
			List<Order> candidates = orderRepository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
				OrderStatus.PENDING,
				now
			);
			if (candidates.isEmpty()) {
				break;
			}

			for (Order candidate : candidates) {
				int updated = orderRepository.updateStatusToExpiredIfCurrent(candidate.getId(), now, OrderStatus.PENDING);
				if (updated != 1) {
					continue;
				}

				expiredCount++;
				orderDecisionLogService.logDecision(
					candidate.getId(),
					OrderStatus.PENDING,
					OrderStatus.EXPIRED,
					null,
					EXPIRE_REASON,
					now
				);
				orderOutboxService.enqueueOrderStatusChanged(
					candidate.getId(),
					OrderStatus.PENDING,
					OrderStatus.EXPIRED,
					now,
					null,
					EXPIRE_REASON
				);
			}
		}

		if (expiredCount > 0) {
			log.info("[OrderExpirationScheduler] 만료 처리된 주문 수: {}", expiredCount);
		}
	}
}
