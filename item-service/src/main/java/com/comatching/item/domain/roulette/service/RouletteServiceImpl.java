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

        // 결제액이 3500이하면 불가능
        if (rouletteType == RouletteType.SPECIAL
                && orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
                        memberInfo.memberId(), todayStart, tomorrowStart) < SPECIAL_ROULETTE_MINIMUM_PAYMENT) {
            throw new BusinessException(ItemErrorCode.NOT_ENOUGH_PAYMENT_FOR_SPECIAL_ROULETTE);
        }

        // 1 ~ 10000 사이의 난수 생성한 후에 db에 확률 range에 맞게 보상 가져오기 (만약 보상의 남은 갯수가 없다면 다시 난수 생성해서 추첨)
        Optional<RouletteReward> rouletteReward;
        do {
            int rouletteNumber = ThreadLocalRandom.current().nextInt(1, 10_001);
            rouletteReward = rouletteRewardRepository
                .findAvailableByRouletteTypeAndRouletteNumber(rouletteType, rouletteNumber);
        } while (rouletteReward.isEmpty());



        // 보상에 따른 아이템 및 아이템 기록 룰렛 기록 추가
        if (rouletteReward.get().getRewardName().equals("풀세트")) {
            itemRepository.save(Item.builder()
                    .memberId(memberInfo.memberId())
                    .itemType(ItemType.OPTION_TICKET)
                    .quantity(3)
                    .expiredAt(LocalDateTime.of(9999, 12, 31, 23, 59, 59))
                    .build());
            itemHistoryRepository.save(ItemHistory.builder()
                    .memberId(memberInfo.memberId())
                    .itemType(ItemType.OPTION_TICKET)
                    .historyType(ItemHistoryType.EVENT)
                    .quantity(3)
                    .description(ItemType.OPTION_TICKET.getName())
                    .build());

            itemRepository.save(Item.builder()
                    .memberId(memberInfo.memberId())
                    .itemType(ItemType.MATCHING_TICKET)
                    .quantity(1)
                    .expiredAt(LocalDateTime.of(9999, 12, 31, 23, 59, 59))
                    .build());
            itemHistoryRepository.save(ItemHistory.builder()
                    .memberId(memberInfo.memberId())
                    .itemType(ItemType.MATCHING_TICKET)
                    .historyType(ItemHistoryType.EVENT)
                    .quantity(1)
                    .description(ItemType.MATCHING_TICKET.getName())
                    .build());

        } else if (rouletteReward.get().getItemType() != null) {
            itemRepository.save(Item.builder()
                    .memberId(memberInfo.memberId())
                    .quantity(rouletteReward.get().getQuantity())
                    .expiredAt(LocalDateTime.of(9999, 12, 31, 23, 59, 59))
                    .itemType(rouletteReward.get().getItemType())
                    .build());
            itemHistoryRepository.save(ItemHistory.builder()
                    .memberId(memberInfo.memberId())
                    .itemType(rouletteReward.get().getItemType())
                    .historyType(ItemHistoryType.EVENT)
                    .quantity(rouletteReward.get().getQuantity())
                    .description(rouletteReward.get().getItemType().getName())
                    .build());
        }

        // 남은 아이템 수 감소
        rouletteReward.get().decreaseRemainingCount();


        rouletteHistoryRepository.save(RouletteHistory.builder()
            .memberId(memberInfo.memberId())
            .reward(rouletteReward.get())
            .rouletteType(rouletteType)
            .build());


        return new RouletteSpinResponse(rouletteReward.get().getRewardName());
    }

    @Override
    public RoulettePageResponse roulettePage(MemberInfo memberInfo, RouletteType rouletteType) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        boolean isParticipated = rouletteHistoryRepository
                .existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
                        memberInfo.memberId(), rouletteType, todayStart, tomorrowStart);


        Long totalPay = rouletteType == RouletteType.SPECIAL
                ? orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
                        memberInfo.memberId(), todayStart, tomorrowStart)
                : null;
        boolean isPossible = !isParticipated
                && (rouletteType == RouletteType.FREE || totalPay >= SPECIAL_ROULETTE_MINIMUM_PAYMENT);

        return new RoulettePageResponse(isPossible, totalPay);
    }
}
