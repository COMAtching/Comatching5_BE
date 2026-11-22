package com.comatching.notification.infra.kafka.auth;

import com.comatching.common.config.kafka.KafkaEventRoute;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.comatching.common.dto.event.member.MemberAuthEvent;
import com.comatching.common.dto.event.member.MemberWithdrawnEvent;
import com.comatching.notification.domain.delivery.DeliveryOutcome;
import com.comatching.notification.domain.delivery.NotificationDeliveryService;
import com.comatching.notification.domain.service.email.EmailService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final EmailService emailService;
    private final NotificationDeliveryService deliveries;

    @KafkaListener(topics = {"member-signup", KafkaEventRoute.ReplayTopics.SIGNUP_MAIL},
            groupId = "notification-signup-group", properties = "max.poll.records=1")
    public void consumeSignup(MemberAuthEvent event) {
        if (event.type() != MemberAuthEvent.EventType.SIGNUP) {
            throw new IllegalArgumentException("가입 메일에는 SIGNUP 이벤트가 필요합니다");
        }
        deliveries.deliver(event.eventId(), "SIGNUP_MAIL", () -> {
            emailService.sendWelcomeEmail(event.email(), event.nickname());
            return DeliveryOutcome.SENT;
        });
    }

    @KafkaListener(topics = {"member-withdraw", KafkaEventRoute.ReplayTopics.WITHDRAW_MAIL},
            groupId = "notification-withdraw-group", properties = "max.poll.records=1")
    public void consumeWithdrawal(MemberWithdrawnEvent event) {
        deliveries.deliver(event.eventId(), "WITHDRAW_MAIL", () -> {
            emailService.sendWithdrawalEmail(event.email());
            return DeliveryOutcome.SENT;
        });
    }
}
