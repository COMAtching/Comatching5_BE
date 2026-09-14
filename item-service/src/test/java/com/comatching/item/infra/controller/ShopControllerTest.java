package com.comatching.item.infra.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.order.config.PaymentOrderProperties;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.order.service.OrderOutboxService;
import com.comatching.item.domain.product.entity.Product;
import com.comatching.item.domain.product.entity.ProductReward;
import com.comatching.item.domain.product.repository.ProductRepository;
import com.comatching.item.domain.product.service.ShopServiceImpl;
import com.comatching.item.infra.client.UserOrderClient;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShopController 할인 뽑기권 API 테스트")
class ShopControllerTest {

	private MockMvc mockMvc;

	@Mock private ProductRepository productRepository;
	@Mock private OrderRepository orderRepository;
	@Mock private ItemRepository itemRepository;
	@Mock private UserOrderClient userOrderClient;
	@Mock private OrderOutboxService orderOutboxService;
	@Mock private PaymentOrderProperties paymentOrderProperties;

	@BeforeEach
	void setUp() {
		ShopServiceImpl shopService = new ShopServiceImpl(
			productRepository,
			orderRepository,
			itemRepository,
			userOrderClient,
			orderOutboxService,
			paymentOrderProperties
		);
		mockMvc = MockMvcBuilders.standaloneSetup(new ShopController(shopService))
			.setCustomArgumentResolvers(new MemberInfoArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
			.build();
	}

	@Test
	@DisplayName("GET /api/v1/shop/products는 800원 할인 상품과 남은 3회를 반환한다")
	void shouldReturnDiscountMatchingTicket() throws Exception {
		Product product = discountMatchingTicket();
		given(productRepository.findActiveProductsWithRewards(true)).willReturn(List.of(product));

		mockMvc.perform(get("/api/v1/shop/products")
				.param("isBundle", "true")
				.header("X-Member-Id", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("(할인) 뽑기권 1개"))
			.andExpect(jsonPath("$.data[0].code").value("DISCOUNT_MATCHING_TICKET_1"))
			.andExpect(jsonPath("$.data[0].price").value(800))
			.andExpect(jsonPath("$.data[0].displayOrder").value(2))
			.andExpect(jsonPath("$.data[0].isBundle").value(true))
			.andExpect(jsonPath("$.data[0].purchaseLimitPerMember").value(3))
			.andExpect(jsonPath("$.data[0].remainingPurchaseCount").value(3))
			.andExpect(jsonPath("$.data[0].rewards[0].itemType").value("MATCHING_TICKET"))
			.andExpect(jsonPath("$.data[0].rewards[0].quantity").value(1));
	}

	@Test
	@DisplayName("POST /api/v1/shop/purchase/{id}는 할인 상품 quantity=2를 거부한다")
	void shouldRejectMultipleDiscountTickets() throws Exception {
		given(productRepository.findById(3L)).willReturn(Optional.of(discountMatchingTicket()));

		mockMvc.perform(post("/api/v1/shop/purchase/3")
				.param("quantity", "2")
				.header("X-Member-Id", "100"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAY-007"));

		then(orderRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("POST /api/v1/shop/purchase/{id}는 승인 3회 이후 추가 구매를 거부한다")
	void shouldRejectFourthDiscountTicketPurchase() throws Exception {
		Product product = discountMatchingTicket();
		given(productRepository.findById(3L)).willReturn(Optional.of(product));
		given(orderRepository.existsActivePendingOrder(eq(100L), any())).willReturn(false);
		given(orderRepository.countApprovedByMemberIdAndProductCode(100L, product.getCode())).willReturn(3L);

		mockMvc.perform(post("/api/v1/shop/purchase/3")
				.param("quantity", "1")
				.header("X-Member-Id", "100"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAY-012"));

		then(orderRepository).should().countActivePendingByMemberIdAndProductCode(
			eq(100L), eq(product.getCode()), any());
		then(orderRepository).should(never()).save(any());
	}

	private Product discountMatchingTicket() {
		Product product = Product.builder()
			.name("(할인) 뽑기권 1개")
			.code("DISCOUNT_MATCHING_TICKET_1")
			.description("")
			.price(800)
			.displayOrder(2)
			.isActive(true)
			.isBundle(true)
			.purchaseLimitPerMember(3)
			.firstPurchaseOnly(false)
			.build();
		ReflectionTestUtils.setField(product, "id", 3L);
		product.addReward(ProductReward.builder()
			.itemType(ItemType.MATCHING_TICKET)
			.quantity(1)
			.build());
		return product;
	}
}
