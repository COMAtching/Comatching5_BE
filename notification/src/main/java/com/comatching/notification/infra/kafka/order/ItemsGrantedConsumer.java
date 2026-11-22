package com.comatching.notification.infra.kafka.order;

import com.comatching.common.config.kafka.KafkaEventRoute.ItemsGrantedGroups;
import com.comatching.common.config.kafka.KafkaEventRoute.ReplayTopics;
import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.notification.domain.delivery.DeliveryOutcome;
import com.comatching.notification.domain.delivery.NotificationDeliveryService;
import com.comatching.notification.domain.service.email.EmailService;
import com.comatching.notification.domain.service.fcm.FCMService;
import com.comatching.notification.infra.client.NotificationRecipientClient;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ItemsGrantedConsumer {
    private final NotificationDeliveryService deliveries;
    private final NotificationRecipientClient recipients;
    private final EmailService mail;
    private final FCMService fcm;

    @KafkaListener(topics = {ItemsGranted.TOPIC, ReplayTopics.ITEMS_GRANTED_MAIL}, groupId = ItemsGrantedGroups.MAIL,
            properties = "max.poll.records=1")
    public void mail(ItemsGranted event) {
        deliveries.deliver(event.eventId(), "GRANT_MAIL", () -> {
            var address = recipients.findEmail(event.memberId());
            if (address.isEmpty()) return DeliveryOutcome.SKIPPED;
            mail.sendItemsGranted(address.get(), event);
            return DeliveryOutcome.SENT;
        });
    }

    @KafkaListener(topics = {ItemsGranted.TOPIC, ReplayTopics.ITEMS_GRANTED_PUSH}, groupId = ItemsGrantedGroups.PUSH,
            properties = {"max.poll.records=1", "max.poll.interval.ms=600000"})
    public void push(ItemsGranted event) {
        deliveries.deliver(event.eventId(), "GRANT_PUSH", () -> fcm.sendItemsGranted(event));
    }
}
