package com.comatching.notification.infra.kafka.chat;

import com.comatching.common.config.kafka.KafkaEventRoute;
import com.comatching.common.dto.event.chat.ChatMessageEvent;
import com.comatching.notification.domain.delivery.DeliveryOutcome;
import com.comatching.notification.domain.delivery.NotificationDeliveryService;
import com.comatching.notification.domain.service.fcm.FCMService;
import com.comatching.notification.domain.service.fcm.FcmDltException;
import com.comatching.notification.infra.client.ChatPushEligibilityClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventConsumer {

    private final FCMService fcmService;
    private final NotificationDeliveryService deliveries;
    private final ChatPushEligibilityClient eligibility;

    @KafkaListener(
            topics = {"chat-message-sent", KafkaEventRoute.ReplayTopics.CHAT_NOTIFICATION},
            groupId = "notification-group",
            properties = {
                    "max.poll.records=1",
                    "max.poll.interval.ms=600000"
            }
    )
    public void handleChatEvent(ChatMessageEvent event) {
        log.info(
                "chat.notification.consume roomId={} targetUserId={} type={}",
                event.roomId(),
                event.targetUserId(),
                event.type()
        );
        if (!StringUtils.hasText(event.messageId()) || event.targetUserId() == null) {
            throw new FcmDltException("채팅 푸시 메시지 ID 또는 수신자 누락", null);
        }
        deliveries.deliver(event.eventId(), "CHAT_PUSH", () -> {
            if (!eligibility.isEligible(event.messageId(), event.targetUserId())) return DeliveryOutcome.SKIPPED;
            return fcmService.sendNotification(event);
        });
    }
}
