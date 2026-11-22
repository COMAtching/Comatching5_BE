package com.comatching.item.domain.order.dto;

import com.comatching.item.domain.order.enums.GrantStatus;
import com.comatching.item.domain.order.enums.OrderStatus;

public record FailedGrantResponse(Long orderId, String requestedItemName, OrderStatus status,
                                  GrantStatus grantStatus, int attempt) {}
