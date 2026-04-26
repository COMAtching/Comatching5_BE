package com.comatching.item.domain.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductBonusReward;
import com.comatching.item.domain.product.entity.ProductReward;

@DataJpaTest
@ContextConfiguration(classes = ProductRepositoryTest.JpaTestConfig.class)
@DisplayName("ProductRepository 테스트")
class ProductRepositoryTest {

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	@DisplayName("활성 상품은 rewards와 bonusRewards를 N+1 없이 단계적으로 조회한다")
	void shouldFetchActiveProductsWithRewardsAndBonusRewards() {
		// given
		Product second = product("두 번째 상품", 2, true);
		second.addReward(reward(ItemType.MATCHING_TICKET, 1));
		second.addBonusReward(bonusReward(ItemType.MATCHING_TICKET, 1));

		Product first = product("첫 번째 상품", 1, true);
		first.addReward(reward(ItemType.MATCHING_TICKET, 2));
		first.addReward(reward(ItemType.OPTION_TICKET, 1));
		first.addBonusReward(bonusReward(ItemType.OPTION_TICKET, 1));

		Product inactive = product("비활성 상품", 3, false);
		inactive.addReward(reward(ItemType.MATCHING_TICKET, 1));

		entityManager.persist(second);
		entityManager.persist(first);
		entityManager.persist(inactive);
		entityManager.flush();
		entityManager.clear();

		// when
		List<Product> products = productRepository.findActiveProductsWithRewards();
		productRepository.fetchBonusRewardsByProductIds(products.stream().map(Product::getId).toList());

		// then
		assertThat(products).extracting(Product::getName).containsExactly("첫 번째 상품", "두 번째 상품");
		assertThat(products).allSatisfy(product -> {
			assertThat(Hibernate.isInitialized(product.getRewards())).isTrue();
			assertThat(Hibernate.isInitialized(product.getBonusRewards())).isTrue();
		});
		assertThat(products.get(0).getRewards()).hasSize(2);
		assertThat(products.get(0).getBonusRewards()).hasSize(1);
	}

	@Test
	@DisplayName("관리자 상품 목록은 비활성 상품까지 정렬하여 조회한다")
	void shouldFetchAllProductsOrdered() {
		// given
		Product inactive = product("비활성 상품", 2, false);
		inactive.addReward(reward(ItemType.MATCHING_TICKET, 1));

		Product active = product("활성 상품", 1, true);
		active.addReward(reward(ItemType.MATCHING_TICKET, 1));

		entityManager.persist(inactive);
		entityManager.persist(active);
		entityManager.flush();
		entityManager.clear();

		// when
		List<Product> products = productRepository.findAllProductsWithRewards();
		productRepository.fetchBonusRewardsByProductIds(products.stream().map(Product::getId).toList());

		// then
		assertThat(products).extracting(Product::getName).containsExactly("활성 상품", "비활성 상품");
		assertThat(products).allSatisfy(product -> {
			assertThat(Hibernate.isInitialized(product.getRewards())).isTrue();
			assertThat(Hibernate.isInitialized(product.getBonusRewards())).isTrue();
		});
	}

	private Product product(String name, int displayOrder, boolean isActive) {
		return Product.builder()
			.name(name)
			.description("상품 설명")
			.price(1000)
			.displayOrder(displayOrder)
			.isActive(isActive)
			.build();
	}

	private ProductReward reward(ItemType itemType, int quantity) {
		return ProductReward.builder()
			.itemType(itemType)
			.quantity(quantity)
			.build();
	}

	private ProductBonusReward bonusReward(ItemType itemType, int quantity) {
		return ProductBonusReward.builder()
			.itemType(itemType)
			.quantity(quantity)
			.build();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = Product.class)
	@EnableJpaRepositories(basePackageClasses = ProductRepository.class)
	static class JpaTestConfig {
	}
}
