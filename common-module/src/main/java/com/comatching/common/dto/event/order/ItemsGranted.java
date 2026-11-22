package com.comatching.common.dto.event.order;

import java.time.LocalDateTime;
import java.util.List;

import com.comatching.common.domain.enums.ItemType;

public record ItemsGranted(String eventId, long orderId, long memberId, LocalDateTime grantedAt,
                           List<GrantedItem> items) {
    public static final String TOPIC = "items-granted";

    public record GrantedItem(ItemType itemType, int quantity) {}
}
