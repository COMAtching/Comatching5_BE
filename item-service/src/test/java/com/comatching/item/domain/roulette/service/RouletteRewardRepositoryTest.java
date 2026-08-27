package com.comatching.item.domain.roulette.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.entity.ItemHistory;
import com.comatching.item.domain.item.repository.ItemHistoryRepository;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.entity.RouletteReward;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.roulette.repository.RouletteHistoryRepository;
import com.comatching.item.domain.roulette.repository.RouletteRewardRepository;

@DataJpaTest
@ContextConfiguration(classes = RouletteRewardRepositoryTest.JpaTestConfig.class)
@Import(RouletteServiceImpl.class)
@DisplayName("Roulette Repository 및 트랜잭션 테스트")
class RouletteRewardRepositoryTest {

	@Autowired
	private RouletteRewardRepository rouletteRewardRepository;

	@Autowired
	private TestEntityManager entityManager;
	@Autowired
	private RouletteHistoryRepository rouletteHistoryRepository;
	@Autowired
	private ItemRepository itemRepository;
	@Autowired
	private RouletteServiceImpl rouletteService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private ItemHistoryRepository itemHistoryRepository;

	@Test
	@DisplayName("난수 범위의 양 끝 1과 10000에 해당하는 보상을 조회한다")
	void shouldFindRewardsAtBothBoundaries() {
		RouletteReward first = persist(reward(RouletteType.FREE, "첫 보상", 1, 5000, null));
		RouletteReward last = persist(reward(RouletteType.FREE, "마지막 보상", 5001, 10000, null));
		flushAndClear();

		List<RouletteReward> numberOne = rouletteRewardRepository
			.findAvailableByRouletteTypeAndRouletteNumber(RouletteType.FREE, 1);
		List<RouletteReward> numberTenThousand = rouletteRewardRepository
			.findAvailableByRouletteTypeAndRouletteNumber(RouletteType.FREE, 10000);

		assertThat(numberOne).extracting(RouletteReward::getId).containsExactly(first.getId());
		assertThat(numberTenThousand).extracting(RouletteReward::getId).containsExactly(last.getId());
	}

	@Test
	@DisplayName("동일 범위의 풀세트 두 행을 ID 오름차순으로 조회한다")
	void shouldFindFullSetRewardsOrderedById() {
		RouletteReward matchingTicket = persist(reward(
			RouletteType.PAID, "풀세트", ItemType.MATCHING_TICKET, 8201, 9200, null));
		RouletteReward optionTicket = persist(reward(
			RouletteType.PAID, "풀세트", ItemType.OPTION_TICKET, 8201, 9200, null));
		flushAndClear();

		List<RouletteReward> rewards = rouletteRewardRepository
			.findAvailableByRouletteTypeAndRouletteNumber(RouletteType.PAID, 8500);

		assertThat(rewards).extracting(RouletteReward::getId)
			.containsExactly(matchingTicket.getId(), optionTicket.getId());
	}

	@Test
	@DisplayName("재고가 소진된 제한 보상은 조회하지 않는다")
	void shouldExcludeOutOfStockReward() {
		persist(reward(RouletteType.PAID, "상품권", 9701, 9900, 0));
		flushAndClear();

		List<RouletteReward> rewards = rouletteRewardRepository
			.findAvailableByRouletteTypeAndRouletteNumber(RouletteType.PAID, 9800);

		assertThat(rewards).isEmpty();
	}

	@Test
	@DisplayName("같은 난수 범위라도 요청한 룰렛 타입의 보상만 조회한다")
	void shouldSeparateRewardsByRouletteType() {
		RouletteReward freeReward = persist(reward(RouletteType.FREE, "무료 보상", 1, 10000, null));
		persist(reward(RouletteType.PAID, "유료 보상", 1, 10000, null));
		flushAndClear();

		List<RouletteReward> rewards = rouletteRewardRepository
			.findAvailableByRouletteTypeAndRouletteNumber(RouletteType.FREE, 5000);

		assertThat(rewards).extracting(RouletteReward::getId).containsExactly(freeReward.getId());
	}

	@Test
	@DisplayName("오늘 같은 회원의 FREE 참여 이력이 있으면 true를 반환한다")
	void shouldFindTodayFreeHistoryForSameMember() {
		persistHistory(1L, RouletteType.FREE);
		flushAndClear();

		boolean exists = rouletteHistoryRepository
			.existsTodayByMemberIdAndRouletteType(1L, RouletteType.FREE);

		assertThat(exists).isTrue();
	}

	@Test
	@DisplayName("과거 FREE 참여 이력만 있으면 false를 반환한다")
	void shouldNotFindPastFreeHistory() {
		RouletteHistory history = persistHistory(1L, RouletteType.FREE);
		entityManager.flush();
		jdbcTemplate.update(
			"UPDATE roulette_history SET participated_at = ? WHERE id = ?",
			Timestamp.valueOf(LocalDate.now().minusDays(1).atTime(12, 0)),
			history.getId());
		entityManager.clear();

		boolean exists = rouletteHistoryRepository
			.existsTodayByMemberIdAndRouletteType(1L, RouletteType.FREE);

		assertThat(exists).isFalse();
	}

	@Test
	@DisplayName("다른 회원의 오늘 FREE 참여 이력은 조회하지 않는다")
	void shouldNotFindAnotherMembersFreeHistory() {
		persistHistory(2L, RouletteType.FREE);
		flushAndClear();

		boolean exists = rouletteHistoryRepository
			.existsTodayByMemberIdAndRouletteType(1L, RouletteType.FREE);

		assertThat(exists).isFalse();
	}

	@Test
	@DisplayName("같은 회원의 오늘 PAID 참여 이력은 FREE 참여로 조회하지 않는다")
	void shouldNotFindPaidHistoryAsFreeHistory() {
		persistHistory(1L, RouletteType.PAID);
		flushAndClear();

		boolean exists = rouletteHistoryRepository
			.existsTodayByMemberIdAndRouletteType(1L, RouletteType.FREE);

		assertThat(exists).isFalse();
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	@DisplayName("아이템 이력 저장 실패 시 아이템과 룰렛 이력을 실제로 롤백한다")
	void shouldRollbackSavedItemWhenItemHistorySaveFails() {
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.executeWithoutResult(status -> rouletteRewardRepository.saveAndFlush(
			reward(RouletteType.FREE, "옵션권 1장", ItemType.OPTION_TICKET, 1, 10000, 2)));
		given(itemHistoryRepository.save(any(ItemHistory.class)))
			.willThrow(new RuntimeException("item history save failed"));

		assertThatThrownBy(() -> rouletteService.spinRoulette(
			new MemberInfo(1L, "member@example.com", "USER"), RouletteType.FREE))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("item history save failed");

		assertThat(itemRepository.count()).isZero();
		assertThat(rouletteHistoryRepository.count()).isZero();
		assertThat(rouletteRewardRepository.findAll())
			.extracting(RouletteReward::getRemainingCount)
			.containsExactly(2);
	}

	private RouletteReward persist(RouletteReward reward) {
		return entityManager.persist(reward);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private RouletteHistory persistHistory(Long memberId, RouletteType rouletteType) {
		RouletteReward historyReward = persist(reward(
			RouletteType.FREE, "꽝", null, 1, 10000, 999999));
		return entityManager.persist(RouletteHistory.builder()
			.memberId(memberId)
			.reward(historyReward)
			.rouletteType(rouletteType)
			.build());
	}

	private RouletteReward reward(
		RouletteType rouletteType,
		String rewardName,
		int rangeStart,
		int rangeEnd,
		Integer remainingCount
	) {
		return reward(rouletteType, rewardName, null, rangeStart, rangeEnd, remainingCount);
	}

	private RouletteReward reward(
		RouletteType rouletteType,
		String rewardName,
		ItemType itemType,
		int rangeStart,
		int rangeEnd,
		Integer remainingCount
	) {
		return RouletteReward.builder()
			.rouletteType(rouletteType)
			.rewardName(rewardName)
			.itemType(itemType)
			.quantity(itemType == null ? 0 : 1)
			.rangeStart(rangeStart)
			.rangeEnd(rangeEnd)
			.remainingCount(remainingCount)
			.build();
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
