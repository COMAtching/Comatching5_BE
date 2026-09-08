package com.comatching.item.domain.roulette.dto.response;

public record RoulettePageResponse(
        boolean isFreeParticipated, // 무료 참여 여부
        boolean isSpecialParticipated, // 스페셜 참여 여부
        long totalPay //  결제 금액
) {
}