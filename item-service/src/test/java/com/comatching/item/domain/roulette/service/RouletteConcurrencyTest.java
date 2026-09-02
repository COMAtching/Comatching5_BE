package com.comatching.item.domain.roulette.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.entity.ItemHistory;
import com.comatching.item.domain.item.repository.ItemHistoryRepository;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.entity.RouletteReward;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.roulette.repository.RouletteHistoryRepository;
import com.comatching.item.domain.roulette.repository.RouletteRewardRepository;
import com.comatching.item.global.exception.ItemErrorCode;

@DataJpaTest
@ContextConfiguration(classes = RouletteConcurrencyTest.JpaTestConfig.class)
@Import(RouletteServiceImpl.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("룰렛 동시성 테스트")
class RouletteConcurrencyTest {

	private static final int REQUEST_COUNT = 20;

	@Autowired
	private RouletteService rouletteService;
	@Autowired
	private RouletteRewardRepository rouletteRewardRepository;
	@Autowired
	private ItemRepository itemRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@MockitoBean
	private OrderRepository orderRepository;

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("단일 행 보상은 동시 추첨 횟수만큼 재고가 차감된다")
	void singleRewardStockIsDecreasedForEveryConcurrentSpin() throws Exception {
		int stock = REQUEST_COUNT;
		Long limitedRewardId = inTransaction(() -> rouletteRewardRepository.save(
			reward(RouletteType.FREE, "한정 보상", 1, 10000, stock)).getId());

		List<Throwable> failures = runConcurrently(REQUEST_COUNT, index ->
			rouletteService.spinRoulette(member(index + 1L), RouletteType.FREE));

		assertThat(failures).isEmpty();
		RouletteReward savedReward = rouletteRewardRepository.findById(limitedRewardId).orElseThrow();
		assertThat(savedReward.getItemType()).isNull();
		assertThat(savedReward.getRemainingCount()).isZero();
		assertThat(count("SELECT COUNT(*) FROM roulette_history WHERE reward_id = ?", limitedRewardId))
			.isEqualTo(REQUEST_COUNT);
		assertThat(count("SELECT COUNT(*) FROM roulette_history"))
			.isEqualTo(REQUEST_COUNT);
	}

	@Test
	@Disabled("참여 이력 조회와 저장 사이의 동시성 제어를 추가할 때 활성화")
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("동일 회원의 무료 룰렛 동시 요청은 하루에 한 번만 성공한다")
	void freeRouletteSucceedsOnlyOnceForSameMember() throws Exception {
		Long memberId = 50L;
		inTransaction(() -> rouletteRewardRepository.save(
			reward(RouletteType.FREE, "기본 보상", 1, 10000, null)));

		List<Throwable> failures = runConcurrently(REQUEST_COUNT, index ->
			rouletteService.spinRoulette(member(memberId), RouletteType.FREE));

		assertThat(failures).hasSize(REQUEST_COUNT - 1)
			.allSatisfy(failure -> {
				assertThat(failure).isInstanceOf(BusinessException.class);
				assertThat(((BusinessException)failure).getErrorCode())
					.isEqualTo(ItemErrorCode.ALREADY_PARTICIPATED_ROULETTE);
			});
		assertThat(count("SELECT COUNT(*) FROM roulette_history WHERE member_id = ?", memberId))
			.isOne();
	}

	private List<Throwable> runConcurrently(int threadCount, ThrowingIntConsumer task) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Throwable>> futures = new ArrayList<>();

		try {
			for (int index = 0; index < threadCount; index++) {
				int taskIndex = index;
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) {
						return new AssertionError("동시 실행 시작 신호를 기다리다 시간 초과");
					}
					try {
						task.accept(taskIndex);
						return null;
					} catch (Throwable throwable) {
						return throwable;
					}
				}));
			}

			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<Throwable> failures = new ArrayList<>();
			for (Future<Throwable> future : futures) {
				Throwable failure = future.get(30, TimeUnit.SECONDS);
				if (failure != null) {
					failures.add(failure);
				}
			}
			return failures;
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}

	private RouletteReward reward(
		RouletteType rouletteType,
		String rewardName,
		int rangeStart,
		int rangeEnd,
		Integer remainingCount
	) {
		return RouletteReward.builder()
			.rouletteType(rouletteType)
			.rewardName(rewardName)
			.quantity(0)
			.rangeStart(rangeStart)
			.rangeEnd(rangeEnd)
			.remainingCount(remainingCount)
			.build();
	}

	private MemberInfo member(Long memberId) {
		return new MemberInfo(memberId, "member" + memberId + "@example.com", "USER");
	}

	private long count(String sql, Object... args) {
		Long result = jdbcTemplate.queryForObject(sql, Long.class, args);
		return result == null ? 0L : result;
	}

	private <T> T inTransaction(java.util.concurrent.Callable<T> callback) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			try {
				return callback.call();
			} catch (Exception exception) {
				throw new IllegalStateException(exception);
			}
		});
	}

	@FunctionalInterface
	private interface ThrowingIntConsumer {
		void accept(int value) throws Exception;
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = {
		RouletteReward.class,
		RouletteHistory.class,
		Item.class,
		ItemHistory.class
	})
	@EnableJpaRepositories(basePackageClasses = {
		RouletteRewardRepository.class,
		RouletteHistoryRepository.class,
		ItemRepository.class,
		ItemHistoryRepository.class
	})
	static class JpaTestConfig {
	}
}
