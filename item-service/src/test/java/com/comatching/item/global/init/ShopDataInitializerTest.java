package com.comatching.item.global.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShopDataInitializer 테스트")
class ShopDataInitializerTest {

	@InjectMocks
	private ShopDataInitializer shopDataInitializer;

	@Mock
	private ProductRepository productRepository;

	@Test
	@DisplayName("상품 데이터가 없으면 확정된 기본 상품을 생성한다")
	@SuppressWarnings("unchecked")
	void shouldCreateConfirmedDefaultProducts() throws Exception {
		// given
		given(productRepository.count()).willReturn(0L);

		// when
		shopDataInitializer.run();

		// then
		ArgumentCaptor<Iterable<Product>> productsCaptor = ArgumentCaptor.forClass(Iterable.class);
		then(productRepository).should().saveAll(productsCaptor.capture());

		List<Product> products = toList(productsCaptor.getValue());
		assertThat(products).hasSize(8);
		assertThat(products).extracting(Product::getCode).containsExactly(
			"FIRST_PURCHASE_SPECIAL_BUNDLE",
			"MINI_BUNDLE",
			"VALUE_BUNDLE",
			"FULL_OPTION_BUNDLE",
			"SUPER_BUNDLE",
			"HYPER_BUNDLE",
			"MATCHING_TICKET_1",
			"OPTION_TICKET_1"
		);

		Product firstPurchaseBundle = product(products, "FIRST_PURCHASE_SPECIAL_BUNDLE");
		assertThat(firstPurchaseBundle.getName()).isEqualTo("첫 구매 특가 번들");
		assertThat(firstPurchaseBundle.getPrice()).isEqualTo(3000);
		assertThat(firstPurchaseBundle.getPurchaseLimitPerMember()).isEqualTo(1);
		assertThat(firstPurchaseBundle.isFirstPurchaseOnly()).isTrue();
		assertThat(rewardQuantity(firstPurchaseBundle, ItemType.MATCHING_TICKET)).isEqualTo(3);
		assertThat(rewardQuantity(firstPurchaseBundle, ItemType.OPTION_TICKET)).isEqualTo(6);

		assertBundle(product(products, "MINI_BUNDLE"), 500, 2, 0, 3);
		assertBundle(product(products, "VALUE_BUNDLE"), 5500, 2, 5, 5);
		assertBundle(product(products, "FULL_OPTION_BUNDLE"), 7000, 1, 5, 15);
		assertBundle(product(products, "SUPER_BUNDLE"), 9500, 1, 10, 5);
		assertBundle(product(products, "HYPER_BUNDLE"), 18000, 2, 15, 30);

		Product matchingTicket = product(products, "MATCHING_TICKET_1");
		assertThat(matchingTicket.isBundle()).isFalse();
		assertThat(matchingTicket.getPurchaseLimitPerMember()).isNull();
		assertThat(matchingTicket.getPrice()).isEqualTo(1000);
		assertThat(rewardQuantity(matchingTicket, ItemType.MATCHING_TICKET)).isEqualTo(1);

		Product optionTicket = product(products, "OPTION_TICKET_1");
		assertThat(optionTicket.isBundle()).isFalse();
		assertThat(optionTicket.getPurchaseLimitPerMember()).isNull();
		assertThat(optionTicket.getPrice()).isEqualTo(200);
		assertThat(rewardQuantity(optionTicket, ItemType.OPTION_TICKET)).isEqualTo(1);
	}

	private void assertBundle(
		Product product,
		int price,
		int purchaseLimitPerMember,
		int matchingTicketQuantity,
		int optionTicketQuantity
	) {
		assertThat(product.isBundle()).isTrue();
		assertThat(product.isFirstPurchaseOnly()).isFalse();
		assertThat(product.getPrice()).isEqualTo(price);
		assertThat(product.getPurchaseLimitPerMember()).isEqualTo(purchaseLimitPerMember);
		assertThat(rewardQuantity(product, ItemType.MATCHING_TICKET)).isEqualTo(matchingTicketQuantity);
		assertThat(rewardQuantity(product, ItemType.OPTION_TICKET)).isEqualTo(optionTicketQuantity);
	}

	private Product product(List<Product> products, String code) {
		return products.stream()
			.filter(product -> product.getCode().equals(code))
			.findFirst()
			.orElseThrow();
	}

	private int rewardQuantity(Product product, ItemType itemType) {
		return product.getRewards().stream()
			.filter(reward -> reward.getItemType() == itemType)
			.mapToInt(reward -> reward.getQuantity())
			.sum();
	}

	private List<Product> toList(Iterable<Product> products) {
		List<Product> result = new ArrayList<>();
		products.forEach(result::add);
		return result;
	}
}
