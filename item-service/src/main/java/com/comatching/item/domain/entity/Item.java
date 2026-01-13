package com.comatching.item.domain.entity;

import java.time.LocalDateTime;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.global.exception.ItemErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "item", indexes = {
	@Index(name = "idx_member_item_usage", columnList = "memberId, itemType, expiredAt")
})
public class Item {

	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemType itemType;

	@Column(nullable = false)
	private int quantity;

	@Column(nullable = false)
	private LocalDateTime expiredAt;

	@Builder
	public Item(Long memberId, ItemType itemType, int quantity, LocalDateTime expiredAt) {
		this.memberId = memberId;
		this.itemType = itemType;
		this.quantity = quantity;
		this.expiredAt = expiredAt;
	}

	public void decrease(int count) {
		if (this.quantity < count) {
			throw new BusinessException(ItemErrorCode.NOT_ENOUGH_ITEM);
		}
		this.quantity -= count;
	}

	public boolean isExpired() {
		return LocalDateTime.now().isAfter(this.expiredAt);
	}
}
