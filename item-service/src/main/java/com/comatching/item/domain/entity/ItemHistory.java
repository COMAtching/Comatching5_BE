package com.comatching.item.domain.entity;

import java.time.LocalDateTime;

import com.comatching.item.domain.enums.ItemHistoryType;
import com.comatching.common.domain.enums.ItemType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "item_history")
public class ItemHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemType itemType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemHistoryType historyType;

	@Column(nullable = false)
	private int quantity;

	private String description;

	private final LocalDateTime createdAt = LocalDateTime.now();

	@Builder
	public ItemHistory(Long memberId, ItemType itemType, ItemHistoryType historyType, int quantity,
		String description) {
		this.memberId = memberId;
		this.itemType = itemType;
		this.historyType = historyType;
		this.quantity = quantity;
		this.description = description;
	}
}
