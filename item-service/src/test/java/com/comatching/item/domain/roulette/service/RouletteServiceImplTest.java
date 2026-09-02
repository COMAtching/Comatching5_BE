package com.comatching.item.domain.roulette.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.entity.ItemHistory;
import com.comatching.item.domain.item.enums.ItemHistoryType;
import com.comatching.item.domain.item.repository.ItemHistoryRepository;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.roulette.dto.response.RoulettePageResponse;
import com.comatching.item.domain.roulette.dto.response.RouletteSpinResponse;
import com.comatching.item.domain.roulette.entity.RouletteHistory;
import com.comatching.item.domain.roulette.entity.RouletteReward;
import com.comatching.item.domain.roulette.enums.RouletteType;
import com.comatching.item.domain.roulette.repository.RouletteHistoryRepository;
import com.comatching.item.domain.roulette.repository.RouletteRewardRepository;
import com.comatching.item.global.exception.ItemErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("RouletteServiceImpl 보상 처리 테스트")
class RouletteServiceImplTest {

	private static final MemberInfo MEMBER = new MemberInfo(1L, "member@example.com", "USER");
	private static final LocalDateTime REWARD_EXPIRED_AT = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

	@Mock
	private RouletteHistoryRepository rouletteHistoryRepository;
	@Mock
	private RouletteRewardRepository rouletteRewardRepository;
	@Mock
	private ItemRepository itemRepository;
	@Mock
	private ItemHistoryRepository itemHistoryRepository;
	@Mock
	private OrderRepository orderRepository;

	@InjectMocks
	private RouletteServiceImpl rouletteService;

	@Test
	@DisplayName("오늘 무료 룰렛에 참여하지 않았으면 참여 가능하다")
	void shouldAllowFreeRouletteWhenNotParticipatedToday() {
		given(rouletteHistoryRepository
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(RouletteType.FREE),
				any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(false);

		RoulettePageResponse response = rouletteService.roulettePage(MEMBER, RouletteType.FREE);

		assertThat(response.isPossible()).isTrue();
		assertThat(response.totalPay()).isNull();
		then(orderRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("오늘 무료 룰렛에 이미 참여했으면 참여할 수 없다")
	void shouldRejectFreeRouletteWhenAlreadyParticipatedToday() {
		given(rouletteHistoryRepository
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(RouletteType.FREE),
				any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(true);

		RoulettePageResponse response = rouletteService.roulettePage(MEMBER, RouletteType.FREE);

		assertThat(response.isPossible()).isFalse();
		assertThat(response.totalPay()).isNull();
		then(orderRepository).shouldHaveNoInteractions();
		then(rouletteHistoryRepository).should()
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(RouletteType.FREE),
				any(LocalDateTime.class), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("오늘 결제액이 3500원이고 스페셜 룰렛에 참여하지 않았으면 참여 가능하다")
	void shouldAllowSpecialRouletteAtMinimumPayment() {
		given(orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
			eq(MEMBER.memberId()), any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(3500L);
		given(rouletteHistoryRepository
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(RouletteType.SPECIAL),
				any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(false);

		RoulettePageResponse response = rouletteService.roulettePage(MEMBER, RouletteType.SPECIAL);

		assertThat(response.isPossible()).isTrue();
		assertThat(response.totalPay()).isEqualTo(3500L);
	}

	@Test
	@DisplayName("오늘 스페셜 룰렛에 이미 참여했으면 결제액이 충분해도 참여할 수 없다")
	void shouldRejectSpecialRouletteWhenAlreadyParticipated() {
		given(orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
			eq(MEMBER.memberId()), any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(10000L);
		given(rouletteHistoryRepository
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(RouletteType.SPECIAL),
				any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(true);

		RoulettePageResponse response = rouletteService.roulettePage(MEMBER, RouletteType.SPECIAL);

		assertThat(response.isPossible()).isFalse();
		assertThat(response.totalPay()).isEqualTo(10000L);
	}

	@Test
	@DisplayName("오늘 결제액이 3500원 미만이면 스페셜 룰렛에 참여할 수 없다")
	void shouldRejectSpecialRouletteWhenPaymentIsInsufficient() {
		given(orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
			eq(MEMBER.memberId()), any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(3499L);
		given(rouletteHistoryRepository
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(RouletteType.SPECIAL),
				any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(false);

		RoulettePageResponse response = rouletteService.roulettePage(MEMBER, RouletteType.SPECIAL);

		assertThat(response.isPossible()).isFalse();
		assertThat(response.totalPay()).isEqualTo(3499L);
	}

	@Test
	@DisplayName("일반 아이템 보상은 아이템과 아이템 이력을 저장하고 보상명을 반환한다")
	void shouldGrantItemRewardAndReturnRewardName() {
		RouletteReward reward = reward(RouletteType.FREE, "옵션권 2장", ItemType.OPTION_TICKET, 2, null);
		givenReward(RouletteType.FREE, reward);

		RouletteSpinResponse response = rouletteService.spinRoulette(MEMBER, RouletteType.FREE);

		ArgumentCaptor<Item> itemCaptor = ArgumentCaptor.forClass(Item.class);
		then(itemRepository).should().save(itemCaptor.capture());
		assertThat(itemCaptor.getValue().getMemberId()).isEqualTo(MEMBER.memberId());
		assertThat(itemCaptor.getValue().getItemType()).isEqualTo(ItemType.OPTION_TICKET);
		assertThat(itemCaptor.getValue().getQuantity()).isEqualTo(2);
		assertThat(itemCaptor.getValue().getExpiredAt()).isEqualTo(REWARD_EXPIRED_AT);

		ArgumentCaptor<ItemHistory> itemHistoryCaptor = ArgumentCaptor.forClass(ItemHistory.class);
		then(itemHistoryRepository).should().save(itemHistoryCaptor.capture());
		assertThat(itemHistoryCaptor.getValue().getMemberId()).isEqualTo(MEMBER.memberId());
		assertThat(itemHistoryCaptor.getValue().getItemType()).isEqualTo(ItemType.OPTION_TICKET);
		assertThat(itemHistoryCaptor.getValue().getHistoryType()).isEqualTo(ItemHistoryType.EVENT);
		assertThat(itemHistoryCaptor.getValue().getQuantity()).isEqualTo(2);
		assertThat(itemHistoryCaptor.getValue().getDescription()).isEqualTo(ItemType.OPTION_TICKET.getName());

		assertHistorySavedWith(reward, RouletteType.FREE);
		assertThat(response.rewardName()).isEqualTo("옵션권 2장");
	}

	@Test
	@DisplayName("풀세트 한 행으로 옵션권과 매칭권을 모두 지급한다")
	void shouldGrantEveryFullSetReward() {
		RouletteReward fullSet = reward(RouletteType.SPECIAL, "풀세트", null, 0, 2);
		givenReward(RouletteType.SPECIAL, fullSet);

		RouletteSpinResponse response = rouletteService.spinRoulette(MEMBER, RouletteType.SPECIAL);

		assertThat(fullSet.getRemainingCount()).isEqualTo(1);
		ArgumentCaptor<Item> itemCaptor = ArgumentCaptor.forClass(Item.class);
		then(itemRepository).should(times(2)).save(itemCaptor.capture());
		assertThat(itemCaptor.getAllValues())
			.extracting(Item::getItemType, Item::getQuantity)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(ItemType.OPTION_TICKET, 3),
				org.assertj.core.groups.Tuple.tuple(ItemType.MATCHING_TICKET, 1)
			);
		ArgumentCaptor<ItemHistory> itemHistoryCaptor = ArgumentCaptor.forClass(ItemHistory.class);
		then(itemHistoryRepository).should(times(2)).save(itemHistoryCaptor.capture());
		assertThat(itemHistoryCaptor.getAllValues())
			.extracting(ItemHistory::getItemType, ItemHistory::getHistoryType, ItemHistory::getQuantity)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple(ItemType.OPTION_TICKET, ItemHistoryType.EVENT, 3),
				org.assertj.core.groups.Tuple.tuple(ItemType.MATCHING_TICKET, ItemHistoryType.EVENT, 1)
			);
		assertHistorySavedWith(fullSet, RouletteType.SPECIAL);
		assertThat(response.rewardName()).isEqualTo("풀세트");
	}

	@Test
	@DisplayName("상품권은 재고만 차감하고 룰렛 이력과 보상명을 남긴다")
	void shouldRecordGiftCardWithoutGrantingItem() {
		RouletteReward reward = reward(RouletteType.SPECIAL, "1만원권 상품권", null, 1, 3);
		givenReward(RouletteType.SPECIAL, reward);

		RouletteSpinResponse response = rouletteService.spinRoulette(MEMBER, RouletteType.SPECIAL);

		assertThat(reward.getRemainingCount()).isEqualTo(2);
		then(itemRepository).should(never()).save(any(Item.class));
		then(itemHistoryRepository).shouldHaveNoInteractions();
		assertHistorySavedWith(reward, RouletteType.SPECIAL);
		assertThat(response.rewardName()).isEqualTo("1만원권 상품권");
	}

	@Test
	@DisplayName("꽝은 아이템을 지급하지 않고 룰렛 이력과 보상명을 남긴다")
	void shouldRecordNoPrizeWithoutGrantingItem() {
		RouletteReward reward = reward(RouletteType.FREE, "꽝", null, 0, null);
		givenReward(RouletteType.FREE, reward);

		RouletteSpinResponse response = rouletteService.spinRoulette(MEMBER, RouletteType.FREE);

		then(itemRepository).should(never()).save(any(Item.class));
		then(itemHistoryRepository).shouldHaveNoInteractions();
		assertHistorySavedWith(reward, RouletteType.FREE);
		assertThat(response.rewardName()).isEqualTo("꽝");
	}

	@Test
	@DisplayName("이미 무료 룰렛에 참여한 회원은 예외가 발생하고 보상을 처리하지 않는다")
	void shouldRejectDuplicatedFreeRouletteParticipation() {
		given(rouletteHistoryRepository
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(RouletteType.FREE),
				any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(true);

		assertThatThrownBy(() -> rouletteService.spinRoulette(MEMBER, RouletteType.FREE))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ItemErrorCode.ALREADY_PARTICIPATED_ROULETTE));

		then(itemRepository).shouldHaveNoInteractions();
		then(rouletteRewardRepository).shouldHaveNoInteractions();
		then(itemHistoryRepository).shouldHaveNoInteractions();
		then(rouletteHistoryRepository).should(never()).save(any(RouletteHistory.class));
	}

	@Test
	@DisplayName("이미 스페셜 룰렛에 참여한 회원은 예외가 발생하고 결제액과 보상을 조회하지 않는다")
	void shouldRejectDuplicatedSpecialRouletteParticipation() {
		given(rouletteHistoryRepository
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(RouletteType.SPECIAL),
				any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(true);

		assertThatThrownBy(() -> rouletteService.spinRoulette(MEMBER, RouletteType.SPECIAL))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ItemErrorCode.ALREADY_PARTICIPATED_ROULETTE));

		then(orderRepository).shouldHaveNoInteractions();
		then(rouletteRewardRepository).shouldHaveNoInteractions();
		then(itemRepository).shouldHaveNoInteractions();
		then(itemHistoryRepository).shouldHaveNoInteractions();
		then(rouletteHistoryRepository).should(never()).save(any(RouletteHistory.class));
	}

	@Test
	@DisplayName("오늘 누적 결제액이 3500원 미만이면 예외가 발생하고 추첨하지 않는다")
	void shouldRejectSpecialRouletteWhenPaymentIsBelowMinimum() {
		given(rouletteHistoryRepository
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(RouletteType.SPECIAL),
				any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(false);
		given(orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
			eq(MEMBER.memberId()), any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(3499L);

		assertThatThrownBy(() -> rouletteService.spinRoulette(MEMBER, RouletteType.SPECIAL))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ItemErrorCode.NOT_ENOUGH_PAYMENT_FOR_SPECIAL_ROULETTE));

		then(rouletteRewardRepository).shouldHaveNoInteractions();
		then(itemRepository).shouldHaveNoInteractions();
		then(itemHistoryRepository).shouldHaveNoInteractions();
		then(rouletteHistoryRepository).should(never()).save(any(RouletteHistory.class));
	}

	@Test
	@DisplayName("선택된 범위의 보상이 비어 있으면 다시 추첨한다")
	void shouldRetryWhenSelectedRewardIsEmpty() {
		RouletteReward reward = reward(RouletteType.FREE, "꽝", null, 0, null);
		given(rouletteRewardRepository
			.findAvailableByRouletteTypeAndRouletteNumber(eq(RouletteType.FREE), anyInt()))
			.willReturn(Optional.empty(), Optional.of(reward));

		RouletteSpinResponse response = rouletteService.spinRoulette(MEMBER, RouletteType.FREE);

		then(rouletteRewardRepository).should(times(2))
			.findAvailableByRouletteTypeAndRouletteNumber(eq(RouletteType.FREE), anyInt());
		assertHistorySavedWith(reward, RouletteType.FREE);
		assertThat(response.rewardName()).isEqualTo("꽝");
	}

	@Test
	@DisplayName("아이템 저장이 실패하면 아이템 이력과 룰렛 이력을 저장하지 않는다")
	void shouldStopWhenItemSaveFails() {
		RouletteReward reward = reward(
			RouletteType.FREE, "옵션권 1장", ItemType.OPTION_TICKET, 1, null);
		givenReward(RouletteType.FREE, reward);
		given(itemRepository.save(any(Item.class))).willThrow(new RuntimeException("item save failed"));

		assertThatThrownBy(() -> rouletteService.spinRoulette(MEMBER, RouletteType.FREE))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("item save failed");

		then(itemHistoryRepository).shouldHaveNoInteractions();
		then(rouletteHistoryRepository).should(never()).save(any(RouletteHistory.class));
	}

	@Test
	@DisplayName("아이템 이력 저장이 실패하면 룰렛 이력을 저장하지 않는다")
	void shouldStopWhenItemHistorySaveFails() {
		RouletteReward reward = reward(
			RouletteType.FREE, "옵션권 1장", ItemType.OPTION_TICKET, 1, null);
		givenReward(RouletteType.FREE, reward);
		given(itemHistoryRepository.save(any(ItemHistory.class)))
			.willThrow(new RuntimeException("item history save failed"));

		assertThatThrownBy(() -> rouletteService.spinRoulette(MEMBER, RouletteType.FREE))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("item history save failed");

		then(itemRepository).should().save(any(Item.class));
		then(rouletteHistoryRepository).should(never()).save(any(RouletteHistory.class));
	}

	@Test
	@DisplayName("룰렛 이력 저장이 실패하면 예외를 그대로 전달한다")
	void shouldPropagateRouletteHistorySaveFailure() {
		RouletteReward reward = reward(RouletteType.FREE, "꽝", null, 0, null);
		givenReward(RouletteType.FREE, reward);
		given(rouletteHistoryRepository.save(any(RouletteHistory.class)))
			.willThrow(new RuntimeException("roulette history save failed"));

		assertThatThrownBy(() -> rouletteService.spinRoulette(MEMBER, RouletteType.FREE))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("roulette history save failed");
	}

	@Test
	@DisplayName("제한 재고는 한 개 차감한다")
	void shouldDecreaseLimitedRemainingCount() {
		RouletteReward reward = reward(RouletteType.SPECIAL, "상품권", null, 1, 2);

		reward.decreaseRemainingCount();

		assertThat(reward.getRemainingCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("무제한 보상은 재고를 변경하지 않는다")
	void shouldNotChangeUnlimitedRemainingCount() {
		RouletteReward reward = reward(RouletteType.FREE, "꽝", null, 0, null);

		reward.decreaseRemainingCount();

		assertThat(reward.getRemainingCount()).isNull();
	}

	@Test
	@DisplayName("소진된 재고는 음수가 되지 않는다")
	void shouldNotDecreaseRemainingCountBelowZero() {
		RouletteReward reward = reward(RouletteType.SPECIAL, "상품권", null, 1, 0);

		reward.decreaseRemainingCount();

		assertThat(reward.getRemainingCount()).isZero();
	}

	private void givenReward(RouletteType rouletteType, RouletteReward reward) {
		given(rouletteHistoryRepository
			.existsByMemberIdAndRouletteTypeAndParticipatedAtGreaterThanEqualAndParticipatedAtLessThan(
				eq(MEMBER.memberId()), eq(rouletteType),
				any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(false);
		if (rouletteType == RouletteType.SPECIAL) {
			given(orderRepository.sumApprovedPriceByMemberIdAndDecidedAtBetween(
				eq(MEMBER.memberId()), any(LocalDateTime.class), any(LocalDateTime.class)))
				.willReturn(3500L);
		}
		given(rouletteRewardRepository
			.findAvailableByRouletteTypeAndRouletteNumber(eq(rouletteType), anyInt()))
			.willReturn(Optional.of(reward));
	}

	private void assertHistorySavedWith(RouletteReward reward, RouletteType rouletteType) {
		ArgumentCaptor<RouletteHistory> historyCaptor = ArgumentCaptor.forClass(RouletteHistory.class);
		then(rouletteHistoryRepository).should().save(historyCaptor.capture());
		assertThat(historyCaptor.getValue().getMemberId()).isEqualTo(MEMBER.memberId());
		assertThat(historyCaptor.getValue().getReward()).isSameAs(reward);
		assertThat(historyCaptor.getValue().getRouletteType()).isEqualTo(rouletteType);
	}

	private RouletteReward reward(
		RouletteType rouletteType,
		String rewardName,
		ItemType itemType,
		int quantity,
		Integer remainingCount
	) {
		return RouletteReward.builder()
			.rouletteType(rouletteType)
			.rewardName(rewardName)
			.itemType(itemType)
			.quantity(quantity)
			.rangeStart(1)
			.rangeEnd(1)
			.remainingCount(remainingCount)
			.build();
	}

}
