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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "roulette_history")
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
    private final LocalDateTime participatedAt = LocalDateTime.now();

    @Column(name = "participation_date")
    private LocalDate participationDate;

    @Builder
    public RouletteHistory(
            Long memberId,
            RouletteReward reward,
            RouletteType rouletteType
    ) {
        this.memberId = memberId;
        this.reward = reward;
        this.rouletteType = rouletteType;
        this.participationDate = rouletteType == RouletteType.FREE
            ? participatedAt.toLocalDate()
            : null;
    }
}
