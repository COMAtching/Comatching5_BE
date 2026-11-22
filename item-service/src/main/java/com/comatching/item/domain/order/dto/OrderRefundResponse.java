package com.comatching.item.domain.order.dto;

import java.time.LocalDateTime;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.enums.RefundStatus;

public record OrderRefundResponse(long orderId, String requestedItemName, int amount, RefundStatus refundStatus,
                                  LocalDateTime startedAt, Long startedBy, LocalDateTime completedAt, Long completedBy) {
    public static OrderRefundResponse from(Order order) {
        return new OrderRefundResponse(order.getId(), order.getRequestedItemName(), order.getExpectedPrice(),
                order.getRefundStatus(), order.getRefundStartedAt(), order.getRefundStartedBy(),
                order.getRefundCompletedAt(), order.getRefundCompletedBy());
    }
}
