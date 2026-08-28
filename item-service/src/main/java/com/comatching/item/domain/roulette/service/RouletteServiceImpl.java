package com.comatching.item.domain.roulette.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.entity.ItemHistory;
import com.comatching.item.domain.item.enums.ItemHistoryType;
import com.comatching.item.domain.item.repository.ItemHistoryRepository;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.roulette.dto.RouletteSpinResponse;
import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.entity.RouletteReward;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.roulette.repository.RouletteHistoryRepository;
import com.comatching.item.domain.roulette.repository.RouletteRewardRepository;
import com.comatching.item.global.exception.ItemErrorCode;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class RouletteServiceImpl implements RouletteService {
    private static final LocalDateTime ROULETTE_REWARD_EXPIRED_AT =
        LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    private final RouletteHistoryRepository rouletteHistoryRepository;
    private final RouletteRewardRepository rouletteRewardRepository;
    private final ItemRepository itemRepository;
    private final ItemHistoryRepository itemHistoryRepository;

    @Override
    @Transactional
    public RouletteSpinResponse spinRoulette(MemberInfo memberInfo, RouletteType rouletteType) {
        // 타입이 무료인 경우 참여여부 검증 + 예외처리
        if (rouletteType == RouletteType.FREE && rouletteHistoryRepository
            .existsTodayByMemberIdAndRouletteType(memberInfo.memberId(), rouletteType)) {
            throw new BusinessException(ItemErrorCode.ALREADY_PARTICIPATED_FREE_ROULETTE);
        }

        if (rouletteType == RouletteType.PAID) {
            Item rouletteTicket = itemRepository
                .findFirstByMemberIdAndItemTypeAndQuantityGreaterThanEqualAndExpiredAtGreaterThanOrderByExpiredAtAscQuantityAsc(
                    memberInfo.memberId(), ItemType.ROULETTE_TICKET, 1, LocalDateTime.now())
                .orElseThrow(() -> new BusinessException(ItemErrorCode.NOT_ENOUGH_ITEM));

            rouletteTicket.decrease(1);

            itemHistoryRepository.save(ItemHistory.builder()
                .memberId(memberInfo.memberId())
                .itemType(ItemType.ROULETTE_TICKET)
                .historyType(ItemHistoryType.USE)
                .quantity(-1)
                .description(ItemType.ROULETTE_TICKET.getName())
                .build());
        }

        // 1 ~ 10000 사이의 난수 생성한 후에 db에 확률 range에 맞게 보상 가져오기 (만약 보상의 남은 갯수가 없다면 다시 난수 생성해서 추첨)
        List<RouletteReward> rouletteRewards;
        do {
            int rouletteNumber = ThreadLocalRandom.current().nextInt(1, 10_001);
            rouletteRewards = rouletteRewardRepository
                .findAvailableByRouletteTypeAndRouletteNumber(rouletteType, rouletteNumber);
        } while (rouletteRewards.isEmpty());

        RouletteReward rouletteReward = rouletteRewards.get(0);

        // 보상에 따른 아이템 및 아이템 기록 룰렛 기록 추가
        rouletteRewards.stream()
            .forEach(RouletteReward::decreaseRemainingCount); // 남은 아이템 수 줄이기

        rouletteRewards.stream()
            .filter(reward -> reward.getItemType() != null)
            .forEach(reward -> {
                itemRepository.save(Item.builder()
                    .memberId(memberInfo.memberId())
                    .itemType(reward.getItemType())
                    .quantity(reward.getQuantity())
                    .expiredAt(ROULETTE_REWARD_EXPIRED_AT)
                    .build());

                itemHistoryRepository.save(ItemHistory.builder()
                    .memberId(memberInfo.memberId())
                    .itemType(reward.getItemType())
                    .historyType(ItemHistoryType.EVENT)
                    .quantity(reward.getQuantity())
                    .description(reward.getItemType().getName())
                    .build());
            });

        try {
            rouletteHistoryRepository.save(RouletteHistory.builder()
                .memberId(memberInfo.memberId())
                .reward(rouletteReward)
                .rouletteType(rouletteType)
                .build());
            rouletteHistoryRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (rouletteType == RouletteType.FREE) {
                throw new BusinessException(ItemErrorCode.ALREADY_PARTICIPATED_FREE_ROULETTE);
            }
            throw exception;
        }

        return new RouletteSpinResponse(rouletteReward.getRewardName());
    }
}
