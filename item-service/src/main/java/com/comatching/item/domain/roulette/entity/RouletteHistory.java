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

    @Column(nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reward_id", nullable = false)
    private RouletteReward reward;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouletteType rouletteType;

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
        this.participationDate = rouletteType == RouletteType.FREE ? LocalDate.now() : null;
    }
}
