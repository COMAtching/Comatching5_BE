package com.comatching.item.global.init;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductReward;
import com.comatching.item.domain.product.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShopDataInitializer implements CommandLineRunner {

	private final ProductRepository productRepository;

	@Override
	@Transactional
	public void run(String... args) throws Exception {
		if (productRepository.count() > 0) {
			log.info("[ShopDataInitializer] 이미 상품 데이터가 존재하여 초기화를 건너뜁니다.");
			return;
		}

		log.info("[ShopDataInitializer] 초기 상품 데이터를 생성합니다...");

		// 1. 뽑기권 X1 (1,000원)
		Product p1 = Product.builder()
			.name("매칭권 1개")
			.price(1000)
			.isActive(true)
			.build();
		p1.addReward(ProductReward.builder()
			.itemType(ItemType.MATCHING_TICKET)
			.quantity(1)
			.build());

		// 2. 뽑기권 X5 + 옵션권 X1 (5,000원)
		Product p2 = Product.builder()
			.name("매칭권 5개 (+옵션권 1개)")
			.price(5000)
			.isActive(true)
			.build();
		p2.addReward(ProductReward.builder()
			.itemType(ItemType.MATCHING_TICKET)
			.quantity(5)
			.build());
		p2.addReward(ProductReward.builder()
			.itemType(ItemType.OPTION_TICKET)
			.quantity(1)
			.build());

		// 3. 뽑기권 X10 (9,000원) - 할인 상품
		Product p3 = Product.builder()
			.name("매칭권 10개 (10% 할인)")
			.price(9000)
			.isActive(true)
			.build();
		p3.addReward(ProductReward.builder()
			.itemType(ItemType.MATCHING_TICKET)
			.quantity(10)
			.build());

		// 4. 옵션권 X1 (300원)
		Product p4 = Product.builder()
			.name("옵션권 1개")
			.price(300)
			.isActive(true)
			.build();
		p4.addReward(ProductReward.builder()
			.itemType(ItemType.OPTION_TICKET)
			.quantity(1)
			.build());
		productRepository.saveAll(List.of(p1, p2, p3, p4));

		log.info("[ShopDataInitializer] 상품 {}개 생성 완료.", 4);
	}
}