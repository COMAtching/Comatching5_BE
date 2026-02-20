package com.comatching.item.domain.product.entity;

import com.comatching.common.domain.enums.ItemType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReward {

	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "product_id")
	private Product product;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemType itemType;

	@Column(nullable = false)
	private int quantity;

	@Builder
	public ProductReward(ItemType itemType, int quantity) {
		this.itemType = itemType;
		this.quantity = quantity;
	}

	// Product에서 호출하기 위한 설정자
	protected void setProduct(Product product) {
		this.product = product;
	}
}