package com.comatching.item.domain.roulette.dto.response;

import java.time.LocalDateTime;

import com.comatching.common.dto.member.AdminGiftCardUserProfileDto;
import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.enums.RouletteType;

public record AdminGiftCardWinnerResponse(
    Long historyId,
    Long memberId,
    String email,
    String realName,
    String nickname,
    String rewardName,
    RouletteType rouletteType,
    LocalDateTime participatedAt
) {
    public static AdminGiftCardWinnerResponse from(
        RouletteHistory history,
        AdminGiftCardUserProfileDto user
    ) {
        return new AdminGiftCardWinnerResponse(
            history.getId(),
            history.getMemberId(),
            user.email(),
            user.realName(),
            user.nickname(),
            history.getReward().getRewardName(),
            history.getRouletteType(),
            history.getParticipatedAt()
        );
    }
}
