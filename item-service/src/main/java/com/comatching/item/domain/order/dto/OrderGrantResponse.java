package com.comatching.item.domain.order.dto;

import com.comatching.item.domain.order.enums.GrantStatus;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.enums.RefundStatus;

public record OrderGrantResponse(Long orderId, OrderStatus status, GrantStatus grantStatus, int attempt,
                                 RefundStatus refundStatus) {}
