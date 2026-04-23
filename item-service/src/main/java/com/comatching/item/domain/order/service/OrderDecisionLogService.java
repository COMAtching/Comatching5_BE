package com.comatching.item.domain.order.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.comatching.item.domain.order.entity.OrderDecisionLog;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.repository.OrderDecisionLogRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderDecisionLogService {

	private final OrderDecisionLogRepository orderDecisionLogRepository;

	public void logDecision(
		Long orderId,
		OrderStatus fromStatus,
		OrderStatus toStatus,
		Long decidedByAdminId,
		String reason,
		LocalDateTime decidedAt
	) {
		OrderDecisionLog log = OrderDecisionLog.builder()
			.orderId(orderId)
			.fromStatus(fromStatus)
			.toStatus(toStatus)
			.decidedByAdminId(decidedByAdminId)
			.reason(reason)
			.decidedAt(decidedAt)
			.build();

		orderDecisionLogRepository.save(log);
	}
}
