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

import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.dto.MyItemsResponse;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.item.domain.item.enums.ItemHistoryType;
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
			verify(historyService).saveHistory(
				memberId,
				type,
				ItemHistoryType.USE,
				-5,
				"매칭권"
			);
		}

		@Test
		@DisplayName("아이템 사용은 사용자/아이템 타입 단위 분산락을 사용한다")
		void shouldUseDistributedLock() throws NoSuchMethodException {
			// when
			var method = ItemServiceImpl.class.getMethod("useItem", Long.class, ItemType.class, int.class);

			// then
			var lock = method.getAnnotation(com.comatching.common.annotation.DistributedLock.class);
			assertThat(lock).isNotNull();
			assertThat(lock.key()).isEqualTo("item:inventory");
			assertThat(lock.identifier()).isEqualTo("#memberId + ':' + #itemType");
		}
	}

	@Nested
	@DisplayName("addItem 메서드")
	class AddItem {

		@Test
		@DisplayName("아이템 지급 이력 설명은 아이템명으로 저장한다")
		void shouldSaveItemNameAsHistoryDescription() {
			// given
			Long memberId = 1L;
			AddItemRequest request = new AddItemRequest(
				ItemType.OPTION_TICKET,
				3,
				ItemRoute.CHARGE,
				30
			);

			// when
			itemService.addItem(memberId, request);

			// then
			verify(historyService).saveHistory(
				memberId,
				ItemType.OPTION_TICKET,
				ItemHistoryType.CHARGE,
				3,
				"옵션권"
			);
		}

		@Test
		@DisplayName("아이템 지급은 사용자/아이템 타입 단위 분산락을 사용한다")
		void shouldUseDistributedLock() throws NoSuchMethodException {
			// when
			var method = ItemServiceImpl.class.getMethod("addItem", Long.class, AddItemRequest.class);

			// then
			var lock = method.getAnnotation(com.comatching.common.annotation.DistributedLock.class);
			assertThat(lock).isNotNull();
			assertThat(lock.key()).isEqualTo("item:inventory");
			assertThat(lock.identifier()).isEqualTo("#memberId + ':' + #request.itemType()");
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
