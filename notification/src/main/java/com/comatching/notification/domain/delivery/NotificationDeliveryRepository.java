package com.comatching.notification.domain.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class NotificationDeliveryRepository {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String eventId, String handlerId, String attemptId) {
        jdbc.update("""
                INSERT INTO notification_delivery (event_id, handler_id, state, updated_at)
                VALUES (?, ?, 'READY', NOW(6)) ON DUPLICATE KEY UPDATE event_id = event_id
                """, eventId, handlerId);
        int claimed = jdbc.update("""
                UPDATE notification_delivery SET state='SENDING', attempt_id=?, updated_at=NOW(6)
                WHERE event_id=? AND handler_id=? AND state IN ('READY', 'RETRYABLE')
                """, attemptId, eventId, handlerId);
        if (claimed == 1) return true;

        String state = jdbc.queryForObject("""
                SELECT state FROM notification_delivery WHERE event_id=? AND handler_id=?
                """, String.class, eventId, handlerId);
        if ("SENT".equals(state) || "SKIPPED".equals(state)) return false;
        throw new DeliveryUncertainException("발송 확인 필요: " + handlerId + "/" + eventId + " " + state, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String eventId, String handlerId, String attemptId, String state) {
        int changed = jdbc.update("""
                UPDATE notification_delivery SET state=?, updated_at=NOW(6)
                WHERE event_id=? AND handler_id=? AND attempt_id=? AND state='SENDING'
                """, state, eventId, handlerId, attemptId);
        if (changed != 1) {
            throw new DeliveryUncertainException("발송 시도 소유권 변경: " + handlerId + "/" + eventId, null);
        }
    }
}
