package com.comatching.item.domain.roulette.enums;

import com.comatching.common.domain.enums.ItemType;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RewardType {
    MATCHING_TICKET(ItemType.MATCHING_TICKET),
    OPTION_TICKET(ItemType.OPTION_TICKET),
    FULL_SET(null),
    GIFT_CARD(null),
    NONE(null);

    private final ItemType itemType;
}
