package com.comatching.item.domain.roulette.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.entity.OrderItem;
import com.comatching.item.domain.order.repository.OrderRepository;

@DataJpaTest
@ContextConfiguration(classes = SpecialRoulettePaymentRepositoryTest.JpaTestConfig.class)
@DisplayName("스페셜 룰렛 결제 금액 조회 테스트")
class SpecialRoulettePaymentRepositoryTest {

	@Autowired
	private OrderRepository orderRepository;

	@Test
	@DisplayName("오늘 승인된 본인 주문만 시작 포함 종료 제외 조건으로 합산한다")
	void shouldSumOnlyMembersApprovedPaymentsWithinToday() {
		Long memberId = 1L;
		LocalDateTime todayStart = LocalDate.now().atStartOfDay();
		LocalDateTime tomorrowStart = todayStart.plusDays(1);

		saveApprovedOrder(memberId, 1000, todayStart);
		saveApprovedOrder(memberId, 2500, tomorrowStart.minusSeconds(1));
		saveApprovedOrder(memberId, 9000, todayStart.minusSeconds(1));
		saveApprovedOrder(memberId, 9000, tomorrowStart);
		saveApprovedOrder(2L, 9000, todayStart.plusHours(1));
		saveRejectedOrder(memberId, 9000, todayStart.plusHours(1));

		long totalPay = orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
			memberId, todayStart, tomorrowStart);

		assertThat(totalPay).isEqualTo(3500L);
		assertThat(orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
			99L, todayStart, tomorrowStart)).isZero();
	}

	private void saveApprovedOrder(Long memberId, int expectedPrice, LocalDateTime decidedAt) {
		Order order = order(memberId, expectedPrice, decidedAt);
		order.approve(decidedAt);
		orderRepository.save(order);
	}

	private void saveRejectedOrder(Long memberId, int expectedPrice, LocalDateTime decidedAt) {
		Order order = order(memberId, expectedPrice, decidedAt);
		order.reject(decidedAt);
		orderRepository.save(order);
	}

	private Order order(Long memberId, int expectedPrice, LocalDateTime requestedAt) {
		return Order.builder()
			.memberId(memberId)
			.productId(1L)
			.productCode("SPECIAL-ROULETTE-TEST")
			.requestedItemName("테스트 상품")
			.requesterRealName("테스트 회원")
			.requesterUsername("test-user")
			.requestedPrice(expectedPrice)
			.expectedPrice(expectedPrice)
			.requestedAt(requestedAt)
			.expiresAt(requestedAt.plusMinutes(10))
			.build();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {Order.class, OrderItem.class})
	@EnableJpaRepositories(basePackageClasses = OrderRepository.class)
	static class JpaTestConfig {
	}
}
