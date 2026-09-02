package com.comatching.item.domain.roulette.dto.response;

public record RoulettePageResponse(
        boolean isPossible,
        Long totalPay
) {
}
