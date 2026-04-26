package com.comatching.item.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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
import com.comatching.common.dto.member.OrdererInfoDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.order.service.OrderOutboxService;
import com.comatching.item.domain.product.dto.PurchasePendingStatusResponse;
import com.comatching.item.domain.product.dto.ProductResponse;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductBonusReward;
import com.comatching.item.domain.product.entity.ProductReward;
import com.comatching.item.domain.product.repository.ProductRepository;
import com.comatching.item.global.exception.ItemErrorCode;
import com.comatching.item.global.exception.PaymentErrorCode;
import com.comatching.item.infra.client.UserOrderClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShopServiceImpl 테스트")
class ShopServiceImplTest {

	@InjectMocks
	private ShopServiceImpl shopService;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private UserOrderClient userOrderClient;

	@Mock
	private OrderOutboxService orderOutboxService;

	@Test
	@DisplayName("상품 ID 기반 요청이면 상품 가격/구성품으로 주문을 생성한다")
	void shouldCreateOrderFromProductSnapshot() {
		// given
		Product product = product("매칭권 10개 (+옵션권 5개)", 5000, true);
		ReflectionTestUtils.setField(product, "id", 3L);
		product.addReward(ProductReward.builder().itemType(ItemType.MATCHING_TICKET).quantity(10).build());
		product.addReward(ProductReward.builder().itemType(ItemType.OPTION_TICKET).quantity(10).build());
		product.addBonusReward(ProductBonusReward.builder().itemType(ItemType.OPTION_TICKET).quantity(5).build());

		given(productRepository.findById(3L)).willReturn(Optional.of(product));
		given(orderRepository.existsActivePendingOrder(eq(100L), any())).willReturn(false);
		given(userOrderClient.getOrdererInfo(100L)).willReturn(new OrdererInfoDto(100L, "홍길동", "길동이"));

		// when
		shopService.requestPurchase(100L, 3L);

		// then
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		then(orderRepository).should().save(orderCaptor.capture());
		Order savedOrder = orderCaptor.getValue();

		assertThat(savedOrder.getMemberId()).isEqualTo(100L);
		assertThat(savedOrder.getRequestedItemName()).isEqualTo("매칭권 10개 (+옵션권 5개)");
		assertThat(savedOrder.getRequesterRealName()).isEqualTo("홍길동");
		assertThat(savedOrder.getRequesterUsername()).isEqualTo("길동이");
		assertThat(savedOrder.getRequestedPrice()).isEqualTo(5000);
		assertThat(savedOrder.getExpectedPrice()).isEqualTo(5000);
		assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(savedOrder.getOrderItems()).hasSize(2);
		assertThat(savedOrder.getOrderItems()).anyMatch(
			item -> item.getItemType() == ItemType.MATCHING_TICKET && item.getQuantity() == 10
		);
		assertThat(savedOrder.getOrderItems()).anyMatch(
			item -> item.getItemType() == ItemType.OPTION_TICKET && item.getQuantity() == 10
		);
		then(orderOutboxService).should().enqueueOrderCreated(savedOrder);
	}

	@Test
	@DisplayName("활성 상품만 노출 순서와 ID 순서대로 조회한다")
	void shouldGetActiveProductsOrderedByDisplayOrderAndId() {
		// given
		Product first = product("첫 번째 상품", 1000, true);
		Product second = product("두 번째 상품", 2000, true);
		ReflectionTestUtils.setField(first, "id", 1L);
		ReflectionTestUtils.setField(second, "id", 2L);
		given(productRepository.findActiveProductsWithRewards()).willReturn(List.of(first, second));

		// when
		List<ProductResponse> responses = shopService.getActiveProducts();

		// then
		assertThat(responses).extracting(ProductResponse::id).containsExactly(1L, 2L);
		assertThat(responses).extracting(ProductResponse::isActive).containsExactly(true, true);
		then(productRepository).should().fetchBonusRewardsByProductIds(List.of(1L, 2L));
	}

	@Test
	@DisplayName("존재하지 않는 상품 ID면 예외가 발생한다")
	void shouldThrowWhenProductNotFound() {
		// given
		given(productRepository.findById(999L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> shopService.requestPurchase(100L, 999L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ItemErrorCode.PRODUCT_NOT_FOUND);
	}

	@Test
	@DisplayName("비활성 상품이면 구매 요청이 불가하다")
	void shouldThrowWhenProductIsNotActive() {
		// given
		Product product = product("비활성 상품", 1000, false);
		given(productRepository.findById(3L)).willReturn(Optional.of(product));

		// when & then
		assertThatThrownBy(() -> shopService.requestPurchase(100L, 3L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ItemErrorCode.PRODUCT_NOT_AVAILABLE);
	}

	@Test
	@DisplayName("대기중 주문이 있으면 새 요청을 막는다")
	void shouldBlockWhenPendingRequestExists() {
		// given
		Product product = product("매칭권 패키지", 9900, true);
		given(productRepository.findById(3L)).willReturn(Optional.of(product));
		given(orderRepository.existsActivePendingOrder(eq(100L), any())).willReturn(true);

		// when & then
		assertThatThrownBy(() -> shopService.requestPurchase(100L, 3L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(PaymentErrorCode.PENDING_REQUEST_ALREADY_EXISTS);
		then(userOrderClient).should(never()).getOrdererInfo(any());
	}

	@Test
	@DisplayName("회원 실명이 없으면 예외가 발생한다")
	void shouldThrowWhenRealNameMissing() {
		// given
		Product product = product("매칭권 패키지", 9900, true);
		given(productRepository.findById(3L)).willReturn(Optional.of(product));
		given(orderRepository.existsActivePendingOrder(eq(100L), any())).willReturn(false);
		given(userOrderClient.getOrdererInfo(100L)).willReturn(new OrdererInfoDto(100L, null, "길동이"));

		// when & then
		assertThatThrownBy(() -> shopService.requestPurchase(100L, 3L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(PaymentErrorCode.REAL_NAME_REQUIRED);
	}

	@Test
	@DisplayName("회원 닉네임이 없으면 예외가 발생한다")
	void shouldThrowWhenUsernameMissing() {
		// given
		Product product = product("매칭권 패키지", 9900, true);
		given(productRepository.findById(3L)).willReturn(Optional.of(product));
		given(orderRepository.existsActivePendingOrder(eq(100L), any())).willReturn(false);
		given(userOrderClient.getOrdererInfo(100L)).willReturn(new OrdererInfoDto(100L, "홍길동", null));

		// when & then
		assertThatThrownBy(() -> shopService.requestPurchase(100L, 3L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(PaymentErrorCode.USERNAME_REQUIRED);
	}

	@Test
	@DisplayName("내 활성 대기 주문이 있으면 PENDING을 반환한다")
	void shouldReturnPendingStatusWhenPendingRequestExists() {
		// given
		given(orderRepository.existsActivePendingOrder(eq(100L), any())).willReturn(true);

		// when
		PurchasePendingStatusResponse response = shopService.getMyPurchaseRequestStatus(100L);

		// then
		assertThat(response.status()).isEqualTo("PENDING");
	}

	@Test
	@DisplayName("내 활성 대기 주문이 없으면 NONE을 반환한다")
	void shouldReturnNoneStatusWhenNoPendingRequest() {
		// given
		given(orderRepository.existsActivePendingOrder(eq(100L), any())).willReturn(false);

		// when
		PurchasePendingStatusResponse response = shopService.getMyPurchaseRequestStatus(100L);

		// then
		assertThat(response.status()).isEqualTo("NONE");
	}

	private Product product(String name, int price, boolean isActive) {
		return Product.builder()
			.name(name)
			.description("상품 설명")
			.price(price)
			.displayOrder(1)
			.isActive(isActive)
			.build();
	}
}
