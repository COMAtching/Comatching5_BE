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

		Product firstPurchaseBundle = product(
			"첫 구매 특가 번들",
			"FIRST_PURCHASE_SPECIAL_BUNDLE",
			"첫 구매 특가 번들",
			3000,
			1,
			true,
			1,
			true
		);
		addReward(firstPurchaseBundle, ItemType.MATCHING_TICKET, 3);
		addReward(firstPurchaseBundle, ItemType.OPTION_TICKET, 6);

		Product miniBundle = product(
			"미니 번들",
			"MINI_BUNDLE",
			"미니 번들",
			500,
			2,
			true,
			2,
			false
		);
		addReward(miniBundle, ItemType.OPTION_TICKET, 3);

		Product valueBundle = product(
			"실속 번들",
			"VALUE_BUNDLE",
			"실속 번들",
			5500,
			3,
			true,
			2,
			false
		);
		addReward(valueBundle, ItemType.MATCHING_TICKET, 5);
		addReward(valueBundle, ItemType.OPTION_TICKET, 5);

		Product fullOptionBundle = product(
			"풀옵션 번들",
			"FULL_OPTION_BUNDLE",
			"풀옵션 번들",
			7000,
			4,
			true,
			1,
			false
		);
		addReward(fullOptionBundle, ItemType.MATCHING_TICKET, 5);
		addReward(fullOptionBundle, ItemType.OPTION_TICKET, 15);

		Product superBundle = product(
			"슈퍼 번들",
			"SUPER_BUNDLE",
			"슈퍼 번들",
			9500,
			5,
			true,
			1,
			false
		);
		addReward(superBundle, ItemType.MATCHING_TICKET, 10);
		addReward(superBundle, ItemType.OPTION_TICKET, 5);

		Product hyperBundle = product(
			"하이퍼 번들",
			"HYPER_BUNDLE",
			"하이퍼 번들",
			18000,
			6,
			true,
			2,
			false
		);
		addReward(hyperBundle, ItemType.MATCHING_TICKET, 15);
		addReward(hyperBundle, ItemType.OPTION_TICKET, 30);

		Product matchingTicket = product(
			"뽑기권 1장",
			"MATCHING_TICKET_1",
			"뽑기권 1장",
			1000,
			7,
			false,
			null,
			false
		);
		addReward(matchingTicket, ItemType.MATCHING_TICKET, 1);

		Product optionTicket = product(
			"옵션권 1장",
			"OPTION_TICKET_1",
			"옵션권 1장",
			200,
			8,
			false,
			null,
			false
		);
		addReward(optionTicket, ItemType.OPTION_TICKET, 1);

		List<Product> products = List.of(
			firstPurchaseBundle,
			miniBundle,
			valueBundle,
			fullOptionBundle,
			superBundle,
			hyperBundle,
			matchingTicket,
			optionTicket
		);
		productRepository.saveAll(products);

		log.info("[ShopDataInitializer] 상품 {}개 생성 완료.", products.size());
	}

	private Product product(
		String name,
		String code,
		String description,
		int price,
		int displayOrder,
		boolean isBundle,
		Integer purchaseLimitPerMember,
		boolean firstPurchaseOnly
	) {
		return Product.builder()
			.name(name)
			.code(code)
			.description(description)
			.price(price)
			.displayOrder(displayOrder)
			.isActive(true)
			.isBundle(isBundle)
			.purchaseLimitPerMember(purchaseLimitPerMember)
			.firstPurchaseOnly(firstPurchaseOnly)
			.build();
	}

	private void addReward(Product product, ItemType itemType, int quantity) {
		product.addReward(ProductReward.builder()
			.itemType(itemType)
			.quantity(quantity)
			.build());
	}
}
