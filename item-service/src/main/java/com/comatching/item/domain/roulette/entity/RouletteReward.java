package com.comatching.item.domain.roulette.entity;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.roulette.enums.RouletteType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouletteReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouletteType rouletteType;

    @Column(nullable = false)
    private String rewardName;

    @Enumerated(EnumType.STRING)
    private ItemType itemType;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int rangeStart;

    @Column(nullable = false)
    private int rangeEnd;

    private Integer remainingCount;

    @Builder
    public RouletteReward(
            RouletteType rouletteType,
            String rewardName,
            ItemType itemType,
            int quantity,
            int rangeStart,
            int rangeEnd,
            Integer remainingCount
    ) {
        this.rouletteType = rouletteType;
        this.rewardName = rewardName;
        this.itemType = itemType;
        this.quantity = quantity;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.remainingCount = remainingCount;
    }

    public void decreaseRemainingCount() {
        if (remainingCount != null && remainingCount > 0) {
            remainingCount--;
        }
    }
}
