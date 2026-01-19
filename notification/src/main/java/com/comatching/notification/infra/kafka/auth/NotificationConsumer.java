package com.comatching.notification.infra.kafka.auth;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.member.MemberAuthEvent;
import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.notification.domain.service.email.EmailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

	private final EmailService emailService;

	/**
	 * 회원가입 이벤트 구독 (member-signup)
	 */
	@KafkaListener(topics = "member-signup", groupId = "notification-signup-group")
	public void consumeSignup(MemberAuthEvent event) {
		emailService.sendWelcomeEmail(event.email(), event.nickname());
	}

	/**
	 * 2. 회원탈퇴 이벤트 구독 (member-withdraw)
	 */
	@KafkaListener(topics = "member-withdraw", groupId = "notification-withdraw-group")
	public void consumeWithdrawal(MemberWithdrawnEvent event) {
		emailService.sendWithdrawalEmail(event.email());
	}
}
