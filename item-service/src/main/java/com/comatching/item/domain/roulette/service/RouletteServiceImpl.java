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
import com.comatching.item.domain.roulette.dto.RouletteSpinResponse;
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

    private final RouletteHistoryRepository rouletteHistoryRepository;
    private final RouletteRewardRepository rouletteRewardRepository;
    private final ItemRepository itemRepository;
    private final ItemHistoryRepository itemHistoryRepository;

    @Override
    @Transactional
    public RouletteSpinResponse spinRoulette(MemberInfo memberInfo, RouletteType rouletteType) {
        //  TODO: 반복적인 코드 메서드로 따로 빼기

        // 타입이 무료인 경우 참여여부 검증 + 예외처리
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        if (rouletteType == RouletteType.FREE && rouletteHistoryRepository
                .existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
                        memberInfo.memberId(), rouletteType, todayStart, tomorrowStart)) {
            throw new BusinessException(ItemErrorCode.ALREADY_PARTICIPATED_FREE_ROULETTE);
        }

        // 유료 룰렛의 경우 티켓 감소
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
                .quantity(-1) //
                .description(ItemType.ROULETTE_TICKET.getName())
                .build());


        }

        Optional<RouletteReward> rouletteReward;
        // 1 ~ 10000 사이의 난수 생성한 후에 db에 확률 range에 맞게 보상 가져오기 (만약 보상의 남은 갯수가 없다면 다시 난수 생성해서 추첨)
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
}
