package com.comatching.item.global.init;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.item.domain.roulette.entity.RouletteReward;
import com.comatching.item.domain.roulette.enums.RewardType;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.roulette.repository.RouletteRewardRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RouletteRewardDataInitializer implements CommandLineRunner {

    private final RouletteRewardRepository rouletteRewardRepository;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (rouletteRewardRepository.count() > 0) {
            log.info("[RouletteRewardDataInitializer] 이미 룰렛 보상 데이터가 존재하여 초기화를 건너뜁니다.");
            return;
        }

        log.info("[RouletteRewardDataInitializer] 초기 룰렛 보상 데이터를 생성합니다...");

        List<RouletteReward> rewards = List.of(

                // FREE Roulette
                reward(
                        RouletteType.FREE,
                        "옵션권 1장",
                        RewardType.OPTION_TICKET,
                        1,
                        1,
                        4500,
                        null
                ),

                reward(
                        RouletteType.FREE,
                        "옵션권 2장",
                        RewardType.OPTION_TICKET,
                        2,
                        4501,
                        7000,
                        null
                ),

                reward(
                        RouletteType.FREE,
                        "꽝",
                        RewardType.NONE,
                        0,
                        7001,
                        8500,
                        null
                ),

                reward(
                        RouletteType.FREE,
                        "뽑기권 1장",
                        RewardType.MATCHING_TICKET,
                        1,
                        8501,
                        9700,
                        null
                ),

                reward(
                        RouletteType.FREE,
                        "풀세트",
                        RewardType.FULL_SET,
                        0,
                        9701,
                        10000,
                        null
                ),

                // SPECIAL Roulette
                reward(
                        RouletteType.SPECIAL,
                        "옵션권 2장",
                        RewardType.OPTION_TICKET,
                        2,
                        1,
                        3900,
                        null
                ),

                reward(
                        RouletteType.SPECIAL,
                        "옵션권 5장",
                        RewardType.OPTION_TICKET,
                        5,
                        3901,
                        6400,
                        null
                ),

                reward(
                        RouletteType.SPECIAL,
                        "뽑기권 1장",
                        RewardType.MATCHING_TICKET,
                        1,
                        6401,
                        8400,
                        null
                ),

                reward(
                        RouletteType.SPECIAL,
                        "풀세트",
                        RewardType.FULL_SET,
                        0,
                        8401,
                        9400,
                        null
                ),

                reward(
                        RouletteType.SPECIAL,
                        "뽑기권 5장",
                        RewardType.MATCHING_TICKET,
                        5,
                        9401,
                        9600,
                        null
                ),

                reward(
                        RouletteType.SPECIAL,
                        "뽑기권 10장",
                        RewardType.MATCHING_TICKET,
                        10,
                        9601,
                        9750,
                        null
                ),

                reward(
                        RouletteType.SPECIAL,
                        "1만원권 상품권",
                        RewardType.GIFT_CARD,
                        0,
                        9751,
                        9900,
                        999
                ),

                reward(
                        RouletteType.SPECIAL,
                        "2만원권 상품권",
                        RewardType.GIFT_CARD,
                        0,
                        9901,
                        10000,
                        999
                )
        );

        rouletteRewardRepository.saveAll(rewards);

        log.info(
                "[RouletteRewardDataInitializer] 룰렛 보상 {}개 생성 완료.",
                rewards.size()
        );
    }

    private RouletteReward reward(
            RouletteType rouletteType,
            String rewardName,
            RewardType rewardType,
            int quantity,
            int rangeStart,
            int rangeEnd,
            Integer remainingCount
    ) {
        return RouletteReward.builder()
                .rouletteType(rouletteType)
                .rewardName(rewardName)
                .rewardType(rewardType)
                .quantity(quantity)
                .rangeStart(rangeStart)
                .rangeEnd(rangeEnd)
                .remainingCount(remainingCount)
                .build();
    }
}