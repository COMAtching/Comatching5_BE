package com.comatching.item.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.item.domain.entity.Item;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.repository.ItemRepository;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

	@InjectMocks
	private ItemServiceImpl itemService;

	@Mock
	private ItemRepository itemRepository;

	@Mock
	private ItemHistoryService historyService;

	@Test
	void useItem_deducts_from_expiring_soon() {
		// given
		Long memberId = 1L;
		ItemType type = ItemType.MATCHING_TICKET;

		// 슬롯 1: 3개 보유 (내일 만료) -> 우선 차감 대상
		Item itemSoon = Item.builder()
			.memberId(memberId).itemType(type).quantity(3)
			.expiredAt(LocalDateTime.now().plusDays(1)).build();

		// 슬롯 2: 5개 보유 (내년 만료) -> 나중 차감 대상
		Item itemLater = Item.builder()
			.memberId(memberId).itemType(type).quantity(5)
			.expiredAt(LocalDateTime.now().plusYears(1)).build();

		// Repository Mocking (정렬되어 반환된다고 가정)
		given(itemRepository.findAllUsableItems(memberId, type))
			.willReturn(List.of(itemSoon, itemLater));

		// when: 5개를 사용한다
		itemService.useItem(memberId, type, 5);

		// then
		// 1. 임박 아이템(3개)은 모두 소진되어 0개가 되어야 함
		assertThat(itemSoon.getQuantity()).isZero();

		// 2. 나중 아이템(5개)에서 나머지 2개가 차감되어 3개가 남아야 함
		assertThat(itemLater.getQuantity()).isEqualTo(3);

		// 3. 이력이 저장되었는지 검증
		verify(historyService, times(1)).saveHistory(any(), any(), any(), anyInt(), anyString());
	}
}