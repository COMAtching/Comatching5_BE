package com.comatching.item.domain.roulette.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.entity.ItemHistory;
import com.comatching.item.domain.item.enums.ItemHistoryType;
import com.comatching.item.domain.item.repository.ItemHistoryRepository;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.roulette.dto.response.RoulettePageResponse;
import com.comatching.item.domain.roulette.dto.response.RouletteSpinResponse;
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

    private static final long SPECIAL_ROULETTE_MINIMUM_PAYMENT = 3500L;

    private final RouletteHistoryRepository rouletteHistoryRepository;
    private final RouletteRewardRepository rouletteRewardRepository;
    private final ItemRepository itemRepository;
    private final ItemHistoryRepository itemHistoryRepository;
    private final OrderRepository orderRepository;

    @Override
    @Transactional
    public RouletteSpinResponse spinRoulette(MemberInfo memberInfo, RouletteType rouletteType) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        // 오늘 참여한 결과 더이상 불가능
        boolean isParticipatedToday = rouletteHistoryRepository
                .existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
                        memberInfo.memberId(), rouletteType, todayStart, tomorrowStart);
        if (isParticipatedToday) {
            throw new BusinessException(ItemErrorCode.ALREADY_PARTICIPATED_ROULETTE);
        }

        // 결제액이 3500미만이면 불가능
        if (rouletteType == RouletteType.SPECIAL
                && orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
                        memberInfo.memberId(), todayStart, tomorrowStart) < SPECIAL_ROULETTE_MINIMUM_PAYMENT) {
            throw new BusinessException(ItemErrorCode.NOT_ENOUGH_PAYMENT_FOR_SPECIAL_ROULETTE);
        }

        RouletteReward rouletteReward = drawAvailableReward(rouletteType);

        // 보상에 따른 아이템 및 아이템 기록 룰렛 기록 추가
        grantReward(memberInfo.memberId(), rouletteReward);

        // 남은 아이템 수 감소
        rouletteReward.decreaseRemainingCount();

        // 기록 남기기
        try {
            rouletteHistoryRepository.save(RouletteHistory.builder()
                .memberId(memberInfo.memberId())
                .reward(rouletteReward)
                .rouletteType(rouletteType)
                .build());
            rouletteHistoryRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ItemErrorCode.ALREADY_PARTICIPATED_ROULETTE);
        }
        return new RouletteSpinResponse(rouletteReward.getRewardName());
    }

    @Override
    public RoulettePageResponse roulettePage(MemberInfo memberInfo) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        boolean isFreeParticipated = rouletteHistoryRepository
                .existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
                        memberInfo.memberId(), RouletteType.FREE, todayStart, tomorrowStart);

        boolean isSpecialParticipated = rouletteHistoryRepository
                .existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
                        memberInfo.memberId(), RouletteType.SPECIAL, todayStart, tomorrowStart);

        long totalPay = orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
                        memberInfo.memberId(), todayStart, tomorrowStart);

        return new RoulettePageResponse(isFreeParticipated, isSpecialParticipated, totalPay);
    }

    private RouletteReward drawAvailableReward(RouletteType rouletteType) {
        Optional<RouletteReward> rouletteReward;
        do {
            int rouletteNumber = ThreadLocalRandom.current().nextInt(1, 10_001);
            rouletteReward = rouletteRewardRepository
                    .findAvailableByRouletteTypeAndRouletteNumber(rouletteType, rouletteNumber);
        } while (rouletteReward.isEmpty()
                && rouletteRewardRepository.existsAvailableByRouletteType(rouletteType));

        if (rouletteReward.isEmpty()) {
            throw new BusinessException(ItemErrorCode.NO_AVAILABLE_ROULETTE_REWARD);
        }

        return rouletteReward.get();
    }

    private void grantReward(Long memberId, RouletteReward rouletteReward) {
        if (rouletteReward.getRewardName().equals("풀세트")) {
            saveRewardItem(memberId, ItemType.OPTION_TICKET, 3);
            saveRewardItem(memberId, ItemType.MATCHING_TICKET, 1);
        } else if (rouletteReward.getItemType() != null) {
            saveRewardItem(memberId, rouletteReward.getItemType(), rouletteReward.getQuantity());
        }
    }



    // 보상을 저장하고 기록을 남겨주는 메서드
    private void saveRewardItem(Long memberId, ItemType itemType, int quantity) {
        itemRepository.save(Item.builder()
                .memberId(memberId)
                .itemType(itemType)
                .quantity(quantity)
                .expiredAt(LocalDateTime.of(9999, 12, 31, 23, 59, 59))
                .build());

        itemHistoryRepository.save(ItemHistory.builder()
                .memberId(memberId)
                .itemType(itemType)
                .historyType(ItemHistoryType.EVENT)
                .quantity(quantity)
                .description(itemType.getName())
                .build());
    }
}
