package com.comatching.item.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.dto.MyItemsResponse;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.item.service.ItemHistoryService;
import com.comatching.item.domain.item.service.ItemServiceImpl;

@ExtendWith(MockitoExtension.class)
@DisplayName("ItemServiceImpl 테스트")
class ItemServiceTest {

	@InjectMocks
	private ItemServiceImpl itemService;

	@Mock
	private ItemRepository itemRepository;

	@Mock
	private ItemHistoryService historyService;

	@Nested
	@DisplayName("useItem 메서드")
	class UseItem {

		@Test
		@DisplayName("만료가 임박한 아이템부터 우선 차감한다")
		void shouldDeductFromExpiringSoonFirst() {
			// given
			Long memberId = 1L;
			ItemType type = ItemType.MATCHING_TICKET;

			Item itemSoon = Item.builder()
				.memberId(memberId)
				.itemType(type)
				.quantity(3)
				.expiredAt(LocalDateTime.now().plusDays(1))
				.build();

			Item itemLater = Item.builder()
				.memberId(memberId)
				.itemType(type)
				.quantity(5)
				.expiredAt(LocalDateTime.now().plusYears(1))
				.build();

			given(itemRepository.findAllUsableItems(memberId, type))
				.willReturn(List.of(itemSoon, itemLater));

			// when
			itemService.useItem(memberId, type, 5);

			// then
			assertThat(itemSoon.getQuantity()).isZero();
			assertThat(itemLater.getQuantity()).isEqualTo(3);
			verify(historyService).saveHistory(any(), any(), any(), anyInt(), anyString());
		}
	}

	@Nested
	@DisplayName("getMyItems 메서드")
	class GetMyItems {

		@Test
		@DisplayName("유효 아이템 목록과 타입별 총수량을 함께 반환한다")
		void shouldReturnItemsAndTypeCounts() {
			// given
			Long memberId = 1L;
			PageRequest pageable = PageRequest.of(0, 10);

			Item matchingItem = Item.builder()
				.memberId(memberId)
				.itemType(ItemType.MATCHING_TICKET)
				.quantity(3)
				.expiredAt(LocalDateTime.now().plusDays(2))
				.build();

			Item optionItem = Item.builder()
				.memberId(memberId)
				.itemType(ItemType.OPTION_TICKET)
				.quantity(1)
				.expiredAt(LocalDateTime.now().plusDays(3))
				.build();

			given(itemRepository.findMyUsableItems(memberId, null, pageable))
				.willReturn(new PageImpl<>(List.of(matchingItem, optionItem), pageable, 2));
			given(itemRepository.sumUsableQuantityByMemberIdAndItemType(memberId, ItemType.MATCHING_TICKET))
				.willReturn(8L);
			given(itemRepository.sumUsableQuantityByMemberIdAndItemType(memberId, ItemType.OPTION_TICKET))
				.willReturn(2L);

			// when
			MyItemsResponse result = itemService.getMyItems(memberId, null, pageable);

			// then
			assertThat(result.items().content()).hasSize(2);
			assertThat(result.matchingTicketCount()).isEqualTo(8L);
			assertThat(result.optionTicketCount()).isEqualTo(2L);
		}
	}
}
