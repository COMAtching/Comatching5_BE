package com.comatching.item.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.item.domain.entity.Item;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.repository.ItemRepository;

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
}
