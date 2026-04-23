package com.comatching.item.domain.product.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private int price;

	@Column(nullable = false)
	private boolean isActive;

	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ProductReward> rewards = new ArrayList<>();

	@Builder
	public Product(String name, int price, boolean isActive) {
		this.name = name;
		this.price = price;
		this.isActive = isActive;
	}

	// 연관관계 편의 메서드 (상품 생성 시 구성품을 쉽게 추가하기 위함)
	public void addReward(ProductReward reward) {
		this.rewards.add(reward);
		reward.setProduct(this);
	}
}