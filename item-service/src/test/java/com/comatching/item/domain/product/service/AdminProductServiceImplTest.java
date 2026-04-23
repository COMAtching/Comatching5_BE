package com.comatching.item.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.product.dto.ProductCreateRequest;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminProductServiceImpl 테스트")
class AdminProductServiceImplTest {

	@InjectMocks
	private AdminProductServiceImpl adminProductService;

	@Mock
	private ProductRepository productRepository;

	@Test
	@DisplayName("상품과 구성품을 등록하면 ProductResponse를 반환한다")
	void shouldCreateProduct() {
		// given
		ProductCreateRequest request = new ProductCreateRequest(
			"신규 번들",
			3300,
			true,
			List.of(
				new ProductCreateRequest.ProductRewardCreateRequest(ItemType.MATCHING_TICKET, 3),
				new ProductCreateRequest.ProductRewardCreateRequest(ItemType.OPTION_TICKET, 1)
			)
		);

		given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

		// when
		ProductResponse response = adminProductService.createProduct(request);

		// then
		ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
		then(productRepository).should().save(productCaptor.capture());
		Product saved = productCaptor.getValue();

		assertThat(saved.getName()).isEqualTo("신규 번들");
		assertThat(saved.getPrice()).isEqualTo(3300);
		assertThat(saved.getRewards()).hasSize(2);
		assertThat(response.name()).isEqualTo("신규 번들");
		assertThat(response.price()).isEqualTo(3300);
		assertThat(response.rewards()).hasSize(2);
	}

	@Test
	@DisplayName("동일 itemType을 중복 등록하면 예외가 발생한다")
	void shouldThrowWhenRewardTypeDuplicated() {
		// given
		ProductCreateRequest request = new ProductCreateRequest(
			"중복 번들",
			1000,
			true,
			List.of(
				new ProductCreateRequest.ProductRewardCreateRequest(ItemType.MATCHING_TICKET, 1),
				new ProductCreateRequest.ProductRewardCreateRequest(ItemType.MATCHING_TICKET, 2)
			)
		);

		// when & then
		assertThatThrownBy(() -> adminProductService.createProduct(request))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(GeneralErrorCode.INVALID_INPUT_VALUE);
	}

	@Test
	@DisplayName("구성품이 비어있으면 예외가 발생한다")
	void shouldThrowWhenRewardsEmpty() {
		// given
		ProductCreateRequest request = new ProductCreateRequest(
			"빈 번들",
			1000,
			true,
			List.of()
		);

		// when & then
		assertThatThrownBy(() -> adminProductService.createProduct(request))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(GeneralErrorCode.INVALID_INPUT_VALUE);
	}
}
