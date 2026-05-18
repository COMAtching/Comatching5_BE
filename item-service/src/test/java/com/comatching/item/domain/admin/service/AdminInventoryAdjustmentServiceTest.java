package com.comatching.item.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.admin.dto.AdminInventoryAction;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.enums.ItemHistoryType;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.item.service.ItemHistoryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminInventoryAdjustmentService 테스트")
class AdminInventoryAdjustmentServiceTest {

	@InjectMocks
	private AdminInventoryAdjustmentService adminInventoryAdjustmentService;

	@Mock
	private ItemRepository itemRepository;

	@Mock
	private ItemHistoryService historyService;

	@Test
	@DisplayName("ADD 액션이면 무제한 아이템 row를 생성하고 관리자 조정 이력을 저장한다")
	void shouldAddInventoryAndSaveAdminHistory() {
		// given
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET,
			4,
			AdminInventoryAction.ADD,
			" 이벤트 보상 "
		);

		// when
		adminInventoryAdjustmentService.adjust(900L, 11L, request);

		// then
		ArgumentCaptor<Item> itemCaptor = ArgumentCaptor.forClass(Item.class);
		then(itemRepository).should().save(itemCaptor.capture());
		Item savedItem = itemCaptor.getValue();
		assertThat(savedItem.getMemberId()).isEqualTo(11L);
		assertThat(savedItem.getItemType()).isEqualTo(ItemType.MATCHING_TICKET);
		assertThat(savedItem.getQuantity()).isEqualTo(4);
		assertThat(savedItem.getExpiredAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 23, 59, 59));

		then(historyService).should().saveHistory(
			11L,
			ItemType.MATCHING_TICKET,
			ItemHistoryType.ADMIN_ADJUSTMENT,
			4,
			"관리자 조정(adminId=900): 이벤트 보상"
		);
	}

	@Test
	@DisplayName("REMOVE 액션이면 여러 row에서 차감하고 관리자 조정 이력을 저장한다")
	void shouldRemoveInventoryAcrossRowsAndSaveAdminHistory() {
		// given
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.OPTION_TICKET,
			5,
			AdminInventoryAction.REMOVE,
			"오지급 회수"
		);
		Item firstItem = item(11L, ItemType.OPTION_TICKET, 3);
		Item secondItem = item(11L, ItemType.OPTION_TICKET, 4);
		given(itemRepository.findAllUsableItems(11L, ItemType.OPTION_TICKET))
			.willReturn(List.of(firstItem, secondItem));

		// when
		adminInventoryAdjustmentService.adjust(901L, 11L, request);

		// then
		assertThat(firstItem.getQuantity()).isZero();
		assertThat(secondItem.getQuantity()).isEqualTo(2);
		then(historyService).should().saveHistory(
			11L,
			ItemType.OPTION_TICKET,
			ItemHistoryType.ADMIN_ADJUSTMENT,
			-5,
			"관리자 조정(adminId=901): 오지급 회수"
		);
	}

	@Test
	@DisplayName("차감 가능한 총수량이 부족하면 예외가 발생하고 이력을 남기지 않는다")
	void shouldThrowWhenRemovingMoreThanAvailable() {
		// given
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET,
			5,
			AdminInventoryAction.REMOVE,
			"오지급 회수"
		);
		given(itemRepository.findAllUsableItems(11L, ItemType.MATCHING_TICKET))
			.willReturn(List.of(item(11L, ItemType.MATCHING_TICKET, 2)));

		// when & then
		assertThatThrownBy(() -> adminInventoryAdjustmentService.adjust(901L, 11L, request))
			.isInstanceOf(BusinessException.class);
		then(historyService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("인벤토리 조정 메서드는 사용자/아이템 타입 단위 분산락을 사용한다")
	void shouldUseMemberItemTypeDistributedLock() throws NoSuchMethodException {
		// when
		var method = AdminInventoryAdjustmentService.class.getMethod(
			"adjust",
			Long.class,
			Long.class,
			AdminInventoryUpdateRequest.class
		);

		// then
		var lock = method.getAnnotation(com.comatching.common.annotation.DistributedLock.class);
		assertThat(lock).isNotNull();
		assertThat(lock.key()).isEqualTo("item:inventory");
		assertThat(lock.identifier()).isEqualTo("#memberId + ':' + #request.itemType()");
	}

	private static Item item(Long memberId, ItemType itemType, int quantity) {
		return Item.builder()
			.memberId(memberId)
			.itemType(itemType)
			.quantity(quantity)
			.expiredAt(LocalDateTime.now().minusDays(1))
			.build();
	}
}
