package com.comatching.item.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.admin.dto.AdminInventoryAction;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.domain.admin.dto.AdminUserDetailResponse;
import com.comatching.item.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.item.domain.item.repository.ItemRepository;
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
	private AdminInventoryDedupeService adminInventoryDedupeService;

	@Mock
	private AdminInventoryAdjustmentService adminInventoryAdjustmentService;

	@Test
	@DisplayName("관리자 사용자 목록 조회 시 사용자 정보와 타입별 아이템 총량을 반환한다")
	void shouldReturnAdminUserList() {
		// given
		AdminUserProfileDto user = new AdminUserProfileDto(1L, "a@comatching.com", "닉네임", Gender.MALE, "https://img");
		given(userAdminClient.getUsers("nick")).willReturn(List.of(user));
		given(itemRepository.sumUsableQuantityByMemberIds(List.of(1L)))
			.willReturn(List.of(
				quantity(1L, ItemType.MATCHING_TICKET, 3L),
				quantity(1L, ItemType.OPTION_TICKET, 7L)
			));

		// when
		List<AdminUserSummaryResponse> result = adminUserItemService.getUsers("nick");

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(1L);
		assertThat(result.get(0).email()).isEqualTo("a@comatching.com");
		assertThat(result.get(0).nickname()).isEqualTo("닉네임");
		assertThat(result.get(0).gender()).isEqualTo(Gender.MALE);
		assertThat(result.get(0).profileImageUrl()).isEqualTo("https://img");
		assertThat(result.get(0).matchingTicketCount()).isEqualTo(3);
		assertThat(result.get(0).optionTicketCount()).isEqualTo(7);
	}

	@Test
	@DisplayName("사용자 상세 조회 시 타입별 아이템 총량을 함께 반환한다")
	void shouldReturnUserDetailWithInventory() {
		// given
		Long memberId = 11L;
		AdminUserProfileDto user = new AdminUserProfileDto(memberId, "user@comatching.com", "유저", Gender.FEMALE, "https://img2");

		given(userAdminClient.getUserDetail(memberId)).willReturn(user);
		given(itemRepository.sumUsableQuantityByMemberIds(List.of(memberId)))
			.willReturn(List.of(quantity(memberId, ItemType.MATCHING_TICKET, 3L)));

		// when
		AdminUserDetailResponse result = adminUserItemService.getUserDetail(memberId);

		// then
		assertThat(result.id()).isEqualTo(memberId);
		assertThat(result.email()).isEqualTo("user@comatching.com");
		assertThat(result.matchingTicketCount()).isEqualTo(3);
		assertThat(result.optionTicketCount()).isZero();
	}

	@Test
	@DisplayName("인벤토리 수정 시 중복 요청을 예약하고 관리자 조정을 수행한다")
	void shouldAddInventoryWhenActionIsAdd() {
		// given
		Long adminId = 900L;
		Long memberId = 15L;
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.OPTION_TICKET,
			5,
			AdminInventoryAction.ADD,
			"보상 누락"
		);
		given(userAdminClient.getUserDetail(memberId))
			.willReturn(new AdminUserProfileDto(memberId, "u@u.com", "u", Gender.MALE, null));

		// when
		adminUserItemService.updateUserInventory(adminId, memberId, request);

		// then
		then(adminInventoryDedupeService).should().reserveOrThrow(memberId, request);
		then(adminInventoryAdjustmentService).should().adjust(adminId, memberId, request);
	}

	@Test
	@DisplayName("REMOVE 액션도 관리자 조정 서비스에 위임한다")
	void shouldRemoveInventoryWhenActionIsRemove() {
		// given
		Long adminId = 901L;
		Long memberId = 21L;
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET,
			2,
			AdminInventoryAction.REMOVE,
			"오지급 회수"
		);
		given(userAdminClient.getUserDetail(memberId))
			.willReturn(new AdminUserProfileDto(memberId, "u@u.com", "u", Gender.MALE, null));

		// when
		adminUserItemService.updateUserInventory(adminId, memberId, request);

		// then
		then(adminInventoryDedupeService).should().reserveOrThrow(memberId, request);
		then(adminInventoryAdjustmentService).should().adjust(adminId, memberId, request);
	}

	@Test
	@DisplayName("memberId가 1 미만이면 예외가 발생한다")
	void shouldThrowWhenMemberIdIsInvalid() {
		// given
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET,
			1,
			AdminInventoryAction.ADD,
			"테스트"
		);

		// when & then
		assertThatThrownBy(() -> adminUserItemService.updateUserInventory(900L, 0L, request))
			.isInstanceOf(BusinessException.class);
	}

	private static ItemRepository.MemberItemQuantity quantity(Long memberId, ItemType itemType, Long quantity) {
		return new ItemRepository.MemberItemQuantity() {
			@Override
			public Long getMemberId() {
				return memberId;
			}

			@Override
			public ItemType getItemType() {
				return itemType;
			}

			@Override
			public Long getQuantity() {
				return quantity;
			}
		};
	}
}
