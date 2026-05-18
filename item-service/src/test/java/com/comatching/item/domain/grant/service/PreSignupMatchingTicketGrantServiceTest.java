package com.comatching.item.domain.grant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.grant.config.PreSignupMatchingTicketGrantProperties;
import com.comatching.item.domain.grant.entity.PreSignupItemGrantLedger;
import com.comatching.item.domain.grant.repository.PreSignupItemGrantLedgerRepository;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.enums.ItemHistoryType;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.item.service.ItemHistoryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("PreSignupMatchingTicketGrantService 테스트")
class PreSignupMatchingTicketGrantServiceTest {

	@Mock
	private PreSignupItemGrantLedgerRepository ledgerRepository;

	@Mock
	private ItemRepository itemRepository;

	@Mock
	private ItemHistoryService historyService;

	@Test
	@DisplayName("SIGNUP 보상으로 매칭권 item row와 EVENT 이력을 생성한다")
	void shouldGrantMatchingTicket() {
		// given
		PreSignupMatchingTicketGrantService service = service(true, 1, "PRE_SIGNUP_2026");
		given(ledgerRepository.existsByCampaignCodeAndMemberIdAndItemType(
			"PRE_SIGNUP_2026",
			11L,
			ItemType.MATCHING_TICKET
		)).willReturn(false);

		// when
		service.grantMatchingTicket(11L);

		// then
		ArgumentCaptor<PreSignupItemGrantLedger> ledgerCaptor =
			ArgumentCaptor.forClass(PreSignupItemGrantLedger.class);
		then(ledgerRepository).should().save(ledgerCaptor.capture());
		PreSignupItemGrantLedger ledger = ledgerCaptor.getValue();
		assertThat(ledger.getCampaignCode()).isEqualTo("PRE_SIGNUP_2026");
		assertThat(ledger.getMemberId()).isEqualTo(11L);
		assertThat(ledger.getItemType()).isEqualTo(ItemType.MATCHING_TICKET);
		assertThat(ledger.getGrantedAt()).isNotNull();

		ArgumentCaptor<Item> itemCaptor = ArgumentCaptor.forClass(Item.class);
		then(itemRepository).should().save(itemCaptor.capture());
		Item item = itemCaptor.getValue();
		assertThat(item.getMemberId()).isEqualTo(11L);
		assertThat(item.getItemType()).isEqualTo(ItemType.MATCHING_TICKET);
		assertThat(item.getQuantity()).isEqualTo(1);
		assertThat(item.getExpiredAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 23, 59, 59));

		then(historyService).should().saveHistory(
			11L,
			ItemType.MATCHING_TICKET,
			ItemHistoryType.EVENT,
			1,
			"매칭권"
		);
	}

	@Test
	@DisplayName("같은 캠페인에서 이미 지급된 사용자는 재지급하지 않는다")
	void shouldSkipDuplicateGrant() {
		// given
		PreSignupMatchingTicketGrantService service = service(true, 1, "PRE_SIGNUP_2026");
		given(ledgerRepository.existsByCampaignCodeAndMemberIdAndItemType(
			"PRE_SIGNUP_2026",
			11L,
			ItemType.MATCHING_TICKET
		)).willReturn(true);

		// when
		service.grantMatchingTicket(11L);

		// then
		then(ledgerRepository).should().existsByCampaignCodeAndMemberIdAndItemType(
			"PRE_SIGNUP_2026",
			11L,
			ItemType.MATCHING_TICKET
		);
		then(ledgerRepository).shouldHaveNoMoreInteractions();
		then(itemRepository).shouldHaveNoInteractions();
		then(historyService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("설정이 꺼져 있으면 지급하지 않는다")
	void shouldSkipWhenDisabled() {
		// given
		PreSignupMatchingTicketGrantService service = service(false, 1, "PRE_SIGNUP_2026");

		// when
		service.grantMatchingTicket(11L);

		// then
		then(ledgerRepository).shouldHaveNoInteractions();
		then(itemRepository).shouldHaveNoInteractions();
		then(historyService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("quantity 설정값만큼 지급한다")
	void shouldGrantConfiguredQuantity() {
		// given
		PreSignupMatchingTicketGrantService service = service(true, 2, "PRE_SIGNUP_2026");
		given(ledgerRepository.existsByCampaignCodeAndMemberIdAndItemType(any(), any(), any()))
			.willReturn(false);

		// when
		service.grantMatchingTicket(11L);

		// then
		ArgumentCaptor<Item> itemCaptor = ArgumentCaptor.forClass(Item.class);
		then(itemRepository).should().save(itemCaptor.capture());
		assertThat(itemCaptor.getValue().getQuantity()).isEqualTo(2);
		then(historyService).should().saveHistory(
			11L,
			ItemType.MATCHING_TICKET,
			ItemHistoryType.EVENT,
			2,
			"매칭권"
		);
	}

	@Test
	@DisplayName("보상 메서드는 memberId 단위 분산락을 사용한다")
	void shouldUseDistributedLock() throws NoSuchMethodException {
		// when
		var method = PreSignupMatchingTicketGrantService.class.getMethod("grantMatchingTicket", Long.class);

		// then
		var lock = method.getAnnotation(com.comatching.common.annotation.DistributedLock.class);
		assertThat(lock).isNotNull();
		assertThat(lock.key()).isEqualTo("pre-signup:item-grant");
		assertThat(lock.identifier()).isEqualTo("#memberId");
	}

	private PreSignupMatchingTicketGrantService service(boolean enabled, int quantity, String campaignCode) {
		return new PreSignupMatchingTicketGrantService(
			new PreSignupMatchingTicketGrantProperties(enabled, quantity, campaignCode),
			ledgerRepository,
			itemRepository,
			historyService
		);
	}
}
