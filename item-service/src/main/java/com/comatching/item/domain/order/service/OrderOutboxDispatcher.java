package com.comatching.item.domain.order.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.item.domain.order.entity.OrderOutboxEvent;
import com.comatching.item.domain.order.enums.OrderOutboxStatus;
import com.comatching.item.domain.order.repository.OrderOutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutboxDispatcher {

	private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

	private final OrderOutboxEventRepository orderOutboxEventRepository;
	private final OrderRealtimePublisher orderRealtimePublisher;

	@Scheduled(fixedDelayString = "${comatching.order.outbox-dispatch-ms:1000}")
	@Transactional
	public void dispatch() {
		LocalDateTime now = LocalDateTime.now();
		orderOutboxEventRepository.resetStaleProcessing(now.minusMinutes(1));

		List<OrderOutboxEvent> events = orderOutboxEventRepository.findTop100ByStatusOrderByIdAsc(OrderOutboxStatus.NEW);
		for (OrderOutboxEvent event : events) {
			if (orderOutboxEventRepository.markProcessingIfNew(event.getId(), now) != 1) {
				continue;
			}

			try {
				orderRealtimePublisher.publish(event);
				orderOutboxEventRepository.markPublished(event.getId(), LocalDateTime.now());
			} catch (Exception e) {
				log.error("[OrderOutboxDispatcher] outbox event dispatch failed. eventId={}", event.getId(), e);
				orderOutboxEventRepository.markRetry(event.getId(), truncateMessage(e.getMessage()));
			}
		}
	}

	private String truncateMessage(String message) {
		if (message == null || message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
			return message;
		}
		return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
	}
}
