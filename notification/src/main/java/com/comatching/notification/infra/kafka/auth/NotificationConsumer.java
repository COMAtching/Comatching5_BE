package com.comatching.notification.infra.kafka.auth;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.member.MemberAuthEvent;
import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
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

	/**
	 * 회원가입 이벤트 구독 (member-signup)
	 */
	@KafkaListener(topics = "member-signup", groupId = "notification-signup-group")
	public void consumeSignup(String message) {
		try {
			MemberAuthEvent event = objectMapper.readValue(message, MemberAuthEvent.class);
			log.info("[Notification] Signup Event Received: memberId={}", event.memberId());

			emailService.sendWelcomeEmail(event.email(), event.nickname());

		} catch (Exception e) {
			log.error("Failed to process signup event: {}", message, e);
		}
	}

	/**
	 * 2. 회원탈퇴 이벤트 구독 (member-withdraw)
	 */
	@KafkaListener(topics = "member-withdraw", groupId = "notification-withdraw-group")
	public void consumeWithdrawal(String message) {
		try {
			MemberWithdrawnEvent event = objectMapper.readValue(message, MemberWithdrawnEvent.class);
			log.info("[Notification] Withdrawal Event Received: memberId={}", event.memberId());

			emailService.sendWithdrawalEmail(event.email());

		} catch (Exception e) {
			log.error("Failed to process withdrawal event: {}", message, e);
		}
	}
}
