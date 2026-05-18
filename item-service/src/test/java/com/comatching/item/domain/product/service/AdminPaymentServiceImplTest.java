package com.comatching.item.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.item.service.ItemService;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.entity.OrderGrantLedger;
import com.comatching.item.domain.order.entity.OrderItem;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.repository.OrderGrantLedgerRepository;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.order.service.OrderDecisionLogService;
import com.comatching.item.domain.order.service.OrderOutboxService;
import com.comatching.item.domain.product.dto.PurchaseRequestDto;
import com.comatching.item.global.exception.PaymentErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPaymentServiceImpl 테스트")
class AdminPaymentServiceImplTest {

	@InjectMocks
	private AdminPaymentServiceImpl adminPaymentService;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderGrantLedgerRepository orderGrantLedgerRepository;

	@Mock
	private OrderDecisionLogService orderDecisionLogService;

	@Mock
	private OrderOutboxService orderOutboxService;

	@Mock
	private ItemService itemService;

	@Test
	@DisplayName("승인 대기 목록을 페이지 단위로 조회한다")
	void shouldGetPendingRequestsWithPagination() {
		// given
		Pageable pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "requestedAt"));
		Order firstOrder = Order.builder()
			.memberId(11L)
			.requestedItemName("매칭 패키지")
			.requesterRealName("홍길동")
			.requesterUsername("길동")
			.requestedPrice(9900)
			.expectedPrice(9900)
			.requestedAt(java.time.LocalDateTime.now())
			.expiresAt(java.time.LocalDateTime.now().plusMinutes(10))
			.build();
		ReflectionTestUtils.setField(firstOrder, "id", 7L);
		firstOrder.addOrderItem(OrderItem.builder().itemType(ItemType.MATCHING_TICKET).quantity(2).build());

		given(orderRepository.findAllByStatusAndExpiresAtAfter(eq(OrderStatus.PENDING), any(), eq(pageable)))
			.willReturn(new PageImpl<>(java.util.List.of(firstOrder), pageable, 5));

		// when
		PagingResponse<PurchaseRequestDto> response = adminPaymentService.getPendingRequests(pageable);

		// then
		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0).requestId()).isEqualTo(7L);
		assertThat(response.content().get(0).matchingTicketQty()).isEqualTo(2);
		assertThat(response.currentPage()).isEqualTo(1);
		assertThat(response.size()).isEqualTo(2);
		assertThat(response.totalElements()).isEqualTo(5);
		assertThat(response.totalPages()).isEqualTo(3);
		then(orderRepository).should()
			.findAllByStatusAndExpiresAtAfter(eq(OrderStatus.PENDING), any(), eq(pageable));
	}

	@Test
	@DisplayName("승인 대기 목록 페이지 크기는 최대 100개로 제한한다")
	void shouldCapPendingRequestPageSize() {
		// given
		Pageable pageable = PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "requestedAt"));
		ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
		given(orderRepository.findAllByStatusAndExpiresAtAfter(eq(OrderStatus.PENDING), any(), any(Pageable.class)))
			.willReturn(new PageImpl<>(java.util.List.of(), PageRequest.of(0, 100), 0));

		// when
		adminPaymentService.getPendingRequests(pageable);

		// then
		then(orderRepository).should()
			.findAllByStatusAndExpiresAtAfter(eq(OrderStatus.PENDING), any(), pageableCaptor.capture());
		assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
	}

	@Test
	@DisplayName("관리자 승인 시 주문 상태를 승인으로 바꾸고 구성품을 지급한다")
	void shouldApprovePendingOrderAndGrantRewards() {
		// given
		Order order = Order.builder()
			.memberId(11L)
			.requestedItemName("매칭 패키지")
			.requesterRealName("홍길동")
			.requesterUsername("길동")
			.requestedPrice(9900)
			.expectedPrice(9900)
			.requestedAt(java.time.LocalDateTime.now())
			.expiresAt(java.time.LocalDateTime.now().plusMinutes(10))
			.build();
		ReflectionTestUtils.setField(order, "id", 7L);
		OrderItem item = OrderItem.builder().itemType(ItemType.MATCHING_TICKET).quantity(2).build();
		order.addOrderItem(item);
		ReflectionTestUtils.setField(item, "id", 71L);

		given(orderRepository.findByIdWithItems(7L)).willReturn(Optional.of(order));
		given(orderRepository.updateStatusIfCurrentAndNotExpired(
			eq(7L),
			eq(OrderStatus.PENDING),
			eq(OrderStatus.APPROVED),
			any(),
			any()
		))
			.willReturn(1);
		given(orderGrantLedgerRepository.existsByOrderIdAndOrderItemId(7L, 71L)).willReturn(false);

		// when
		adminPaymentService.approvePurchase(7L, 900L);

		// then
		ArgumentCaptor<AddItemRequest> addItemRequestCaptor = ArgumentCaptor.forClass(AddItemRequest.class);
		then(itemService).should().addItem(eq(11L), addItemRequestCaptor.capture());
		then(orderGrantLedgerRepository).should().save(any(OrderGrantLedger.class));
		then(orderDecisionLogService).should().logDecision(eq(7L), eq(OrderStatus.PENDING), eq(OrderStatus.APPROVED), eq(900L), eq(null), any());
		then(orderOutboxService).should().enqueueOrderStatusChanged(eq(7L), eq(OrderStatus.PENDING), eq(OrderStatus.APPROVED), any(), eq(900L), eq(null));
		AddItemRequest addItemRequest = addItemRequestCaptor.getValue();
		org.assertj.core.api.Assertions.assertThat(addItemRequest.itemType()).isEqualTo(ItemType.MATCHING_TICKET);
		org.assertj.core.api.Assertions.assertThat(addItemRequest.quantity()).isEqualTo(2);
		org.assertj.core.api.Assertions.assertThat(addItemRequest.route()).isEqualTo(ItemRoute.CHARGE);
		org.assertj.core.api.Assertions.assertThat(addItemRequest.expiredAt()).isEqualTo(36500);
	}

	@Test
	@DisplayName("관리자 거부 시 상태만 변경하고 구성품 지급은 하지 않는다")
	void shouldRejectPendingOrder() {
		// given
		Order order = Order.builder()
			.memberId(11L)
			.requestedItemName("매칭 패키지")
			.requesterRealName("홍길동")
			.requesterUsername("길동")
			.requestedPrice(9900)
			.expectedPrice(9900)
			.requestedAt(java.time.LocalDateTime.now())
			.expiresAt(java.time.LocalDateTime.now().plusMinutes(10))
			.build();
		ReflectionTestUtils.setField(order, "id", 8L);

		given(orderRepository.findByIdWithItems(8L)).willReturn(Optional.of(order));
		given(orderRepository.updateStatusIfCurrentAndNotExpired(
			eq(8L),
			eq(OrderStatus.PENDING),
			eq(OrderStatus.REJECTED),
			any(),
			any()
		))
			.willReturn(1);

		// when
		adminPaymentService.rejectPurchase(8L, 900L);

		// then
		then(itemService).should(never()).addItem(any(), any());
		then(orderDecisionLogService).should().logDecision(eq(8L), eq(OrderStatus.PENDING), eq(OrderStatus.REJECTED), eq(900L), eq(null), any());
		then(orderOutboxService).should().enqueueOrderStatusChanged(eq(8L), eq(OrderStatus.PENDING), eq(OrderStatus.REJECTED), any(), eq(900L), eq(null));
	}

	@Test
	@DisplayName("이미 처리된 요청 승인 시 예외가 발생한다")
	void shouldThrowWhenApprovingAlreadyProcessedRequest() {
		// given
		Order order = Order.builder()
			.memberId(11L)
			.requestedItemName("매칭 패키지")
			.requesterRealName("홍길동")
			.requesterUsername("길동")
			.requestedPrice(9900)
			.expectedPrice(9900)
			.requestedAt(java.time.LocalDateTime.now())
			.expiresAt(java.time.LocalDateTime.now().plusMinutes(10))
			.build();
		ReflectionTestUtils.setField(order, "id", 9L);
		order.reject(java.time.LocalDateTime.now());

		given(orderRepository.findByIdWithItems(9L)).willReturn(Optional.of(order));
		given(orderRepository.findById(9L)).willReturn(Optional.of(order));
		given(orderRepository.updateStatusIfCurrentAndNotExpired(
			eq(9L),
			eq(OrderStatus.PENDING),
			eq(OrderStatus.APPROVED),
			any(),
			any()
		)).willReturn(0);

		// when & then
		assertThatThrownBy(() -> adminPaymentService.approvePurchase(9L, 900L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(PaymentErrorCode.ALREADY_PROCESSED);
	}

	@Test
	@DisplayName("존재하지 않는 요청 거부 시 예외가 발생한다")
	void shouldThrowWhenRejectingNotFoundRequest() {
		// given
		given(orderRepository.findByIdWithItems(10L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> adminPaymentService.rejectPurchase(10L, 900L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(PaymentErrorCode.REQUEST_NOT_FOUND);
	}

	@Test
	@DisplayName("만료된 요청 승인 시 만료 예외가 발생한다")
	void shouldThrowWhenApprovingExpiredRequest() {
		// given
		Order order = Order.builder()
			.memberId(11L)
			.requestedItemName("매칭 패키지")
			.requesterRealName("홍길동")
			.requesterUsername("길동")
			.requestedPrice(9900)
			.expectedPrice(9900)
			.requestedAt(java.time.LocalDateTime.now().minusMinutes(20))
			.expiresAt(java.time.LocalDateTime.now().minusMinutes(10))
			.build();
		ReflectionTestUtils.setField(order, "id", 11L);

		given(orderRepository.findByIdWithItems(11L)).willReturn(Optional.of(order));
		given(orderRepository.updateStatusToExpiredIfCurrent(eq(11L), any(), eq(OrderStatus.PENDING))).willReturn(1);

		// when & then
		assertThatThrownBy(() -> adminPaymentService.approvePurchase(11L, 900L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(PaymentErrorCode.REQUEST_EXPIRED);
		then(orderDecisionLogService).should().logDecision(eq(11L), eq(OrderStatus.PENDING), eq(OrderStatus.EXPIRED), eq(null), any(), any());
		then(orderOutboxService).should().enqueueOrderStatusChanged(eq(11L), eq(OrderStatus.PENDING), eq(OrderStatus.EXPIRED), any(), eq(null), any());
	}
}
