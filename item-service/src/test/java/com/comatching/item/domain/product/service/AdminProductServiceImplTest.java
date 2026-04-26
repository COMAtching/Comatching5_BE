package com.comatching.item.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.product.dto.ProductCreateRequest;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductBonusReward;
import com.comatching.item.domain.product.entity.ProductReward;
import com.comatching.item.domain.product.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminProductServiceImpl 테스트")
class AdminProductServiceImplTest {

	@InjectMocks
	private AdminProductServiceImpl adminProductService;

	@Mock
	private ProductRepository productRepository;

	@Test
	@DisplayName("상품과 구성품, 보너스 구성품을 등록하면 ProductResponse를 반환한다")
	void shouldCreateProduct() {
		// given
		ProductCreateRequest request = new ProductCreateRequest(
			"신규 번들",
			"매칭권과 옵션권을 함께 충전해요.",
			3300,
			7,
			true,
			List.of(
				reward(ItemType.MATCHING_TICKET, 3),
				reward(ItemType.OPTION_TICKET, 1)
			),
			List.of(reward(ItemType.OPTION_TICKET, 1))
		);

		given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

		// when
		ProductResponse response = adminProductService.createProduct(request);

		// then
		ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
		then(productRepository).should().save(productCaptor.capture());
		Product saved = productCaptor.getValue();

		assertThat(saved.getName()).isEqualTo("신규 번들");
		assertThat(saved.getDescription()).isEqualTo("매칭권과 옵션권을 함께 충전해요.");
		assertThat(saved.getPrice()).isEqualTo(3300);
		assertThat(saved.getDisplayOrder()).isEqualTo(7);
		assertThat(saved.isActive()).isTrue();
		assertThat(saved.getRewards()).hasSize(2);
		assertThat(saved.getBonusRewards()).hasSize(1);
		assertThat(response.name()).isEqualTo("신규 번들");
		assertThat(response.description()).isEqualTo("매칭권과 옵션권을 함께 충전해요.");
		assertThat(response.price()).isEqualTo(3300);
		assertThat(response.displayOrder()).isEqualTo(7);
		assertThat(response.isActive()).isTrue();
		assertThat(response.rewards()).hasSize(2);
		assertThat(response.bonusRewards()).hasSize(1);
		assertThat(response.bonusRewards().get(0).itemType()).isEqualTo(ItemType.OPTION_TICKET);
		assertThat(response.bonusRewards().get(0).quantity()).isEqualTo(1);
	}

	@Test
	@DisplayName("관리자 상품 목록은 비활성 상품도 포함한다")
	void shouldGetAllProductsForAdmin() {
		// given
		Product active = product("활성 상품", "활성 설명", 1000, 1, true);
		Product inactive = product("비활성 상품", "비활성 설명", 2000, 2, false);
		ReflectionTestUtils.setField(active, "id", 1L);
		ReflectionTestUtils.setField(inactive, "id", 2L);

		given(productRepository.findAllProductsWithRewards()).willReturn(List.of(active, inactive));

		// when
		List<ProductResponse> responses = adminProductService.getProducts();

		// then
		assertThat(responses).extracting(ProductResponse::id).containsExactly(1L, 2L);
		assertThat(responses).extracting(ProductResponse::isActive).containsExactly(true, false);
		then(productRepository).should().fetchBonusRewardsByProductIds(List.of(1L, 2L));
	}

	@Test
	@DisplayName("상품 삭제는 실제 삭제가 아니라 비활성화한다")
	void shouldDeactivateProductWhenDeleted() {
		// given
		Product product = product("판매 상품", "판매 설명", 1000, 1, true);
		given(productRepository.findById(1L)).willReturn(Optional.of(product));

		// when
		adminProductService.deleteProduct(1L);

		// then
		assertThat(product.isActive()).isFalse();
	}

	@Test
	@DisplayName("존재하지 않는 상품을 삭제하면 404 예외가 발생한다")
	void shouldThrowWhenDeleteProductNotFound() {
		// given
		given(productRepository.findById(999L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> adminProductService.deleteProduct(999L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(GeneralErrorCode.NOT_FOUND);
	}

	@Test
	@DisplayName("동일 itemType을 중복 등록하면 예외가 발생한다")
	void shouldThrowWhenRewardTypeDuplicated() {
		// given
		ProductCreateRequest request = request(
			List.of(reward(ItemType.MATCHING_TICKET, 1), reward(ItemType.MATCHING_TICKET, 2)),
			List.of()
		);

		// when & then
		assertInvalidInput(request);
	}

	@Test
	@DisplayName("보너스 itemType을 중복 등록하면 예외가 발생한다")
	void shouldThrowWhenBonusRewardTypeDuplicated() {
		// given
		ProductCreateRequest request = request(
			List.of(reward(ItemType.MATCHING_TICKET, 3)),
			List.of(reward(ItemType.MATCHING_TICKET, 1), reward(ItemType.MATCHING_TICKET, 1))
		);

		// when & then
		assertInvalidInput(request);
	}

	@Test
	@DisplayName("보너스가 실제 구성품에 없는 itemType이면 예외가 발생한다")
	void shouldThrowWhenBonusRewardTypeNotInRewards() {
		// given
		ProductCreateRequest request = request(
			List.of(reward(ItemType.MATCHING_TICKET, 3)),
			List.of(reward(ItemType.OPTION_TICKET, 1))
		);

		// when & then
		assertInvalidInput(request);
	}

	@Test
	@DisplayName("보너스 수량이 실제 지급 수량보다 크면 예외가 발생한다")
	void shouldThrowWhenBonusRewardQuantityExceedsRewardQuantity() {
		// given
		ProductCreateRequest request = request(
			List.of(reward(ItemType.MATCHING_TICKET, 3)),
			List.of(reward(ItemType.MATCHING_TICKET, 4))
		);

		// when & then
		assertInvalidInput(request);
	}

	private void assertInvalidInput(ProductCreateRequest request) {
		assertThatThrownBy(() -> adminProductService.createProduct(request))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(GeneralErrorCode.INVALID_INPUT_VALUE);
	}

	private ProductCreateRequest request(
		List<ProductCreateRequest.ProductRewardCreateRequest> rewards,
		List<ProductCreateRequest.ProductRewardCreateRequest> bonusRewards
	) {
		return new ProductCreateRequest("신규 번들", "상품 설명", 1000, 1, true, rewards, bonusRewards);
	}

	private ProductCreateRequest.ProductRewardCreateRequest reward(ItemType itemType, int quantity) {
		return new ProductCreateRequest.ProductRewardCreateRequest(itemType, quantity);
	}

	private Product product(String name, String description, int price, int displayOrder, boolean isActive) {
		Product product = Product.builder()
			.name(name)
			.description(description)
			.price(price)
			.displayOrder(displayOrder)
			.isActive(isActive)
			.build();
		product.addReward(ProductReward.builder().itemType(ItemType.MATCHING_TICKET).quantity(1).build());
		product.addBonusReward(ProductBonusReward.builder().itemType(ItemType.MATCHING_TICKET).quantity(1).build());
		return product;
	}
}
