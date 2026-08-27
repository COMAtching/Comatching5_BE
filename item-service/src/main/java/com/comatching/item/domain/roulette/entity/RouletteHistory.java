package com.comatching.item.domain.roulette.entity;

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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouletteHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reward_id", nullable = false)
    private RouletteReward reward;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouletteType rouletteType;

    private final LocalDateTime participatedAt = LocalDateTime.now();

    @Builder
    public RouletteHistory(
            Long memberId,
            RouletteReward reward,
            RouletteType rouletteType
    ) {
        this.memberId = memberId;
        this.reward = reward;
        this.rouletteType = rouletteType;
    }
}