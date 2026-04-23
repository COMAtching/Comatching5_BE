package com.comatching.item.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.admin.dto.AdminInventoryAction;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.domain.admin.dto.AdminUserDetailResponse;
import com.comatching.item.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.item.service.ItemService;
import com.comatching.item.infra.client.UserAdminClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserItemServiceImpl 테스트")
class AdminUserItemServiceImplTest {

	@InjectMocks
	private AdminUserItemServiceImpl adminUserItemService;

	@Mock
	private UserAdminClient userAdminClient;

	@Mock
	private ItemRepository itemRepository;

	@Mock
	private ItemService itemService;

	@Test
	@DisplayName("관리자 사용자 목록 조회 시 닉네임/이메일 정보를 그대로 반환한다")
	void shouldReturnAdminUserList() {
		// given
		AdminUserProfileDto user = new AdminUserProfileDto(1L, "a@comatching.com", "닉네임", Gender.MALE, "https://img");
		given(userAdminClient.getUsers("nick")).willReturn(List.of(user));

		// when
		List<AdminUserSummaryResponse> result = adminUserItemService.getUsers("nick");

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(1L);
		assertThat(result.get(0).email()).isEqualTo("a@comatching.com");
		assertThat(result.get(0).nickname()).isEqualTo("닉네임");
		assertThat(result.get(0).gender()).isEqualTo(Gender.MALE);
		assertThat(result.get(0).profileImageUrl()).isEqualTo("https://img");
	}

	@Test
	@DisplayName("사용자 상세 조회 시 인벤토리 목록을 함께 반환한다")
	void shouldReturnUserDetailWithInventory() {
		// given
		Long memberId = 11L;
		AdminUserProfileDto user = new AdminUserProfileDto(memberId, "user@comatching.com", "유저", Gender.FEMALE, "https://img2");
		Item item = Item.builder()
			.memberId(memberId)
			.itemType(ItemType.MATCHING_TICKET)
			.quantity(3)
			.expiredAt(LocalDateTime.of(2026, 3, 31, 23, 59))
			.build();
		ReflectionTestUtils.setField(item, "id", 99L);

		given(userAdminClient.getUserDetail(memberId)).willReturn(user);
		given(itemRepository.findAllUsableItemsForAdmin(memberId)).willReturn(List.of(item));

		// when
		AdminUserDetailResponse result = adminUserItemService.getUserDetail(memberId);

		// then
		assertThat(result.id()).isEqualTo(memberId);
		assertThat(result.email()).isEqualTo("user@comatching.com");
		assertThat(result.items()).hasSize(1);
		assertThat(result.items().get(0).itemId()).isEqualTo(99L);
		assertThat(result.items().get(0).itemType()).isEqualTo(ItemType.MATCHING_TICKET);
		assertThat(result.items().get(0).quantity()).isEqualTo(3);
	}

	@Test
	@DisplayName("ADD 액션이면 CHARGE 경로로 아이템을 추가한다")
	void shouldAddInventoryWhenActionIsAdd() {
		// given
		Long memberId = 15L;
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.OPTION_TICKET,
			5,
			AdminInventoryAction.ADD
		);
		given(userAdminClient.getUserDetail(memberId))
			.willReturn(new AdminUserProfileDto(memberId, "u@u.com", "u", Gender.MALE, null));
		willDoNothing().given(itemService).addItem(eq(memberId), any(AddItemRequest.class));

		// when
		adminUserItemService.updateUserInventory(memberId, request);

		// then
		ArgumentCaptor<AddItemRequest> requestCaptor = ArgumentCaptor.forClass(AddItemRequest.class);
		then(itemService).should().addItem(eq(memberId), requestCaptor.capture());
		AddItemRequest addItemRequest = requestCaptor.getValue();
		assertThat(addItemRequest.itemType()).isEqualTo(ItemType.OPTION_TICKET);
		assertThat(addItemRequest.quantity()).isEqualTo(5);
		assertThat(addItemRequest.route()).isEqualTo(ItemRoute.CHARGE);
	}

	@Test
	@DisplayName("REMOVE 액션이면 아이템 차감을 수행한다")
	void shouldRemoveInventoryWhenActionIsRemove() {
		// given
		Long memberId = 21L;
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET,
			2,
			AdminInventoryAction.REMOVE
		);
		given(userAdminClient.getUserDetail(memberId))
			.willReturn(new AdminUserProfileDto(memberId, "u@u.com", "u", Gender.MALE, null));
		willDoNothing().given(itemService).useItem(memberId, ItemType.MATCHING_TICKET, 2);

		// when
		adminUserItemService.updateUserInventory(memberId, request);

		// then
		then(itemService).should().useItem(memberId, ItemType.MATCHING_TICKET, 2);
	}

	@Test
	@DisplayName("memberId가 1 미만이면 예외가 발생한다")
	void shouldThrowWhenMemberIdIsInvalid() {
		// given
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET,
			1,
			AdminInventoryAction.ADD
		);

		// when & then
		assertThatThrownBy(() -> adminUserItemService.updateUserInventory(0L, request))
			.isInstanceOf(BusinessException.class);
	}
}
