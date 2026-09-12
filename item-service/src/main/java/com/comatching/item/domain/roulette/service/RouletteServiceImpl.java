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
import com.comatching.item.domain.roulette.enums.RewardType;
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
        LocalDateTime participatedAt = LocalDateTime.now();
        LocalDate participationDate = participatedAt.toLocalDate();
        LocalDateTime todayStart = participationDate.atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        // 오늘 참여한 결과 더이상 불가능
        boolean isParticipatedToday = rouletteHistoryRepository
                .existsByMemberIdAndRouletteTypeAndParticipationDate(
                        memberInfo.memberId(), rouletteType, participationDate);
        if (isParticipatedToday) {
            throw new BusinessException(ItemErrorCode.ALREADY_PARTICIPATED_ROULETTE);
        }

        // 결제액이 3500미만이면 불가능
        if (rouletteType == RouletteType.SPECIAL
                && orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
                        memberInfo.memberId(), todayStart, tomorrowStart) < SPECIAL_ROULETTE_MINIMUM_PAYMENT) {
            throw new BusinessException(ItemErrorCode.NOT_ENOUGH_PAYMENT_FOR_SPECIAL_ROULETTE);
        }

        // 난수로 추첨
        RouletteReward rouletteReward = drawAvailableReward(rouletteType);

        // 보상에 따른 아이템 및 아이템 기록 룰렛 기록 추가
        boolean rewardGranted = grantReward(memberInfo.memberId(), rouletteReward);

        // 제한 재고만 감소시키며, remainingCount가 null인 무제한 보상은 유지한다.
        rouletteReward.decreaseRemainingCount();

        // 기록 남기기
        try {
            rouletteHistoryRepository.saveAndFlush(RouletteHistory.builder()
                    .memberId(memberInfo.memberId())
                    .reward(rouletteReward)
                    .rouletteType(rouletteType)
                    .rewardGranted(rewardGranted)
                    .participatedAt(participatedAt)
                    .build());
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ItemErrorCode.ALREADY_PARTICIPATED_ROULETTE);
        }
        return new RouletteSpinResponse(rouletteReward.getRewardName());
    }


    @Override
    public RoulettePageResponse roulettePage(MemberInfo memberInfo) {
        // 오늘 날짜
        LocalDate participationDate = LocalDate.now();
        LocalDateTime todayStart = participationDate.atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        // 오늘 무료 룰렛 참여 여부
        boolean isFreeParticipated = rouletteHistoryRepository
                .existsByMemberIdAndRouletteTypeAndParticipationDate(
                        memberInfo.memberId(), RouletteType.FREE, participationDate);

        // 오늘 유료 룰렛 참여 여부
        boolean isSpecialParticipated = rouletteHistoryRepository
                .existsByMemberIdAndRouletteTypeAndParticipationDate(
                        memberInfo.memberId(), RouletteType.SPECIAL, participationDate);

        // 오늘 결제액
        long totalPay = orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
                        memberInfo.memberId(), todayStart, tomorrowStart);

        return new RoulettePageResponse(isFreeParticipated, isSpecialParticipated, totalPay);
    }





    // 난수로 보상을 추첨하는 메서드
    private RouletteReward drawAvailableReward(RouletteType rouletteType) {
        Optional<RouletteReward> rouletteReward;

        do {
            // 1 ~ 10000 사이의 난수 생성
            int rouletteNumber = ThreadLocalRandom.current().nextInt(1, 10_001);

            // 무제한(null)이거나 남은 수량이 1 이상인 보상을 조회한다.
            rouletteReward = rouletteRewardRepository
                    .findAvailableByRouletteTypeAndRouletteNumber(rouletteType, rouletteNumber);

        // 선택한 범위의 제한 재고가 소진됐으면 새 난수로 다시 추첨한다.
        } while (rouletteReward.isEmpty());

        // 상품권 외 보상은 무제한 재고로 운영하며, 전체 보상 소진 시 처리 정책은 확정되지 않아 예외 처리를 비활성화한다.
//        if (rouletteReward.isEmpty()) {
//            throw new BusinessException(ItemErrorCode.NO_AVAILABLE_ROULETTE_REWARD);
//        }

        return rouletteReward.get();
    }

    private boolean grantReward(Long memberId, RouletteReward rouletteReward) {
        RewardType rewardType = rouletteReward.getRewardType();

        // 풀세트의 경우
        if (rewardType == RewardType.FULL_SET) {
            saveRewardItem(memberId, ItemType.OPTION_TICKET, 3);
            saveRewardItem(memberId, ItemType.MATCHING_TICKET, 1);

            // 지급 완료 처리
            return true;
        // 아닐경우 아이템 지급
        } else if (rewardType.getItemType() != null) {
            saveRewardItem(memberId, rewardType.getItemType(), rouletteReward.getQuantity());

            // 지급 완료 처리
            return true;
        }

        // 상품권은 관리자 지급 전이고, 꽝은 지급된 보상이 없으므로 false로 기록한다.
        return false;
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
