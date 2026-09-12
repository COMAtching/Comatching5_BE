package com.comatching.item.domain.roulette.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.comatching.item.domain.roulette.enums.RouletteType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "roulette_history",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_roulette_history_member_type_date",
        columnNames = {"member_id", "roulette_type", "participation_date"}
    )
)
public class RouletteHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reward_id", nullable = false)
    private RouletteReward reward;

    @Enumerated(EnumType.STRING)
    @Column(name = "roulette_type", nullable = false)
    private RouletteType rouletteType;

    @Column(name = "participated_at",nullable = false)
    private LocalDateTime participatedAt;

    @Column(name = "participation_date", nullable = false)
    private LocalDate participationDate;

    @Column(name = "reward_granted", nullable = false)
    // 일반 아이템과 풀세트는 룰렛 처리 중 지급이 끝나면 true가 된다.
    // 상품권은 관리자가 실제 지급한 뒤 지급 완료 API를 호출하기 전까지 false로 남는다.
    private boolean rewardGranted;

    @Builder
    public RouletteHistory(
            Long memberId,
            RouletteReward reward,
            RouletteType rouletteType,
            boolean rewardGranted,
            LocalDateTime participatedAt
    ) {
        this.memberId = memberId;
        this.reward = reward;
        this.rouletteType = rouletteType;
        this.participatedAt = participatedAt != null ? participatedAt : LocalDateTime.now();
        this.participationDate = this.participatedAt.toLocalDate();
        this.rewardGranted = rewardGranted;
    }

    public void markRewardAsGranted() {
        // 관리자가 상품권을 실제 지급한 뒤 지급 완료 상태로 변경한다.
        this.rewardGranted = true;
    }
}
