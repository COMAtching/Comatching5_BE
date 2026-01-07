package com.comatching.notification.infra.kafka.auth;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.MemberAuthEvent;
import com.comatching.notification.domain.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

	private final EmailService emailService;
	private final ObjectMapper objectMapper;

	@KafkaListener(topics = "member-auth", groupId = "notification-group")
	public void consumeMemberAuthEvent(String message) {
		try {
			MemberAuthEvent event = objectMapper.readValue(message, MemberAuthEvent.class);
			log.info("Kafka Event Received: type={}, memberId={}", event.type(), event.memberId());

			switch (event.type()) {
				case SIGNUP -> emailService.sendWelcomeEmail(event.email(), event.nickname());
				case WITHDRAWAL -> emailService.sendWithdrawalEmail(event.email());
				default -> log.warn("Unsupported event type: {}", event.type());
			}

		} catch (Exception e) {
			log.error("Error processing message: {}", message, e);
		}
	}
}
