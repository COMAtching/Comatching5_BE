package com.comatching.common.dto.event.order;

import java.time.LocalDateTime;

public record RefundCompleted(String eventId, long orderId, long memberId, int amount, LocalDateTime completedAt) {
    public static final String TOPIC = "refund-completed";
}
