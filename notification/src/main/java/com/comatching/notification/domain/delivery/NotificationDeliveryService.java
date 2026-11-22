package com.comatching.notification.domain.delivery;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {
    private final NotificationDeliveryRepository ledger;

    public void deliver(String eventId, String handlerId, Supplier<DeliveryOutcome> send) {
        String attemptId = UUID.randomUUID().toString();
        if (!ledger.claim(eventId, handlerId, attemptId)) return;

        DeliveryOutcome outcome;
        try {
            outcome = send.get();
        } catch (DeliveryNotSentException e) {
            ledger.finish(eventId, handlerId, attemptId, "RETRYABLE");
            throw e;
        } catch (RuntimeException e) {
            ledger.finish(eventId, handlerId, attemptId, "UNKNOWN");
            throw new DeliveryUncertainException("외부 발송 결과 확인 필요: " + handlerId + "/" + eventId, e);
        }
        // 수락 후 DB 장애 시 SENDING이 남는다. 다음 소비가 자동 발송하지 않는다.
        ledger.finish(eventId, handlerId, attemptId, outcome.name());
    }
}
