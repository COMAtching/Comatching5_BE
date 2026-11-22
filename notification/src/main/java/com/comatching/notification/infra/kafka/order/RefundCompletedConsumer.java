package com.comatching.notification.infra.kafka.order;

import com.comatching.common.config.kafka.KafkaEventRoute;
import com.comatching.common.dto.event.order.RefundCompleted;
import com.comatching.notification.domain.delivery.DeliveryOutcome;
import com.comatching.notification.domain.delivery.NotificationDeliveryService;
import com.comatching.notification.domain.service.email.EmailService;
import com.comatching.notification.infra.client.NotificationRecipientClient;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefundCompletedConsumer {
    private final NotificationDeliveryService deliveries;
    private final NotificationRecipientClient recipients;
    private final EmailService mail;

    @KafkaListener(topics = {RefundCompleted.TOPIC, KafkaEventRoute.ReplayTopics.REFUND_MAIL},
            groupId = KafkaEventRoute.Groups.REFUND_MAIL, properties = "max.poll.records=1")
    public void mail(RefundCompleted event) {
        deliveries.deliver(event.eventId(), "REFUND_MAIL", () -> {
            var address = recipients.findEmail(event.memberId());
            if (address.isEmpty()) return DeliveryOutcome.SKIPPED;
            mail.sendRefundCompleted(address.get(), event);
            return DeliveryOutcome.SENT;
        });
    }
}
