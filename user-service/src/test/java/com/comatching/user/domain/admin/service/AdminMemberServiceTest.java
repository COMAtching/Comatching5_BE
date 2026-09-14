package com.comatching.user.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import com.comatching.user.domain.admin.user.service.AdminMemberServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.user.domain.admin.user.dto.AdminInventoryAction;
import com.comatching.user.domain.admin.user.dto.AdminInventoryCounts;
import com.comatching.user.domain.admin.user.dto.AdminInventoryUpdateRequest;
import com.comatching.user.domain.admin.user.dto.AdminUserSummaryResponse;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.global.exception.UserErrorCode;
import com.comatching.user.infra.client.ItemAdminClient;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.codec.DecodeException;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMemberServiceImpl 테스트")
class AdminMemberServiceTest {

	@InjectMocks
	private AdminMemberServiceImpl adminMemberService;

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private ItemAdminClient itemAdminClient;

	@Test
	@DisplayName("조회된 사용자가 0명이면 item-service를 호출하지 않는다")
	void shouldNotCallItemServiceWhenNoUsersFound() {
		// given
		PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));
		given(memberRepository.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, null, pageable))
			.willReturn(new PageImpl<>(List.of(), pageable, 0));

		// when
		PagingResponse<AdminUserSummaryResponse> result = adminMemberService.getUsers(null, pageable);

		// then
		assertThat(result.content()).isEmpty();
		then(itemAdminClient).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("조회된 사용자가 20명이면 item-service를 정확히 한 번 호출한다")
	void shouldCallItemServiceOnceFor20Users() {
		assertInventoryBatching(20, 1, List.of(20));
	}

	@Test
	@DisplayName("조회된 사용자가 100명이면 item-service를 정확히 한 번 호출한다")
	void shouldCallItemServiceOnceFor100Users() {
		assertInventoryBatching(100, 1, List.of(100));
	}

	@Test
	@DisplayName("조회된 사용자가 101명이면 100명과 1명으로 나눠 호출하고 결과를 병합한다")
	void shouldCallItemServiceTwiceFor101Users() {
		assertInventoryBatching(101, 2, List.of(100, 1));
	}

	@Test
	@DisplayName("조회된 사용자가 250명이면 100명, 100명, 50명으로 나눠 호출하고 결과를 병합한다")
	void shouldCallItemServiceThreeTimesFor250Users() {
		assertInventoryBatching(250, 3, List.of(100, 100, 50));
	}

	@Test
	@DisplayName("사용자 목록과 인벤토리 수량을 함께 조회한다")
	void shouldReturnUsersWithInventoryCounts() {
		// given
		PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));
		Member member1 = createMemberWithProfile(1L, "user1@test.com", "홍길동", "닉네임1", Gender.FEMALE, "https://img1");
		Member member2 = createMemberWithProfile(2L, "user2@test.com", "김철수", "닉네임2", Gender.MALE, "https://img2");

		given(memberRepository.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, null, pageable))
			.willReturn(new PageImpl<>(List.of(member1, member2), pageable, 2));
		given(itemAdminClient.getInventoryCounts(anyList()))
			.willReturn(Map.of(1L, new AdminInventoryCounts(3L, 1L)));

		// when
		PagingResponse<AdminUserSummaryResponse> result = adminMemberService.getUsers(null, pageable);

		// then
		assertThat(result.content()).hasSize(2);
		assertThat(result.totalElements()).isEqualTo(2);

		AdminUserSummaryResponse first = result.content().get(0);
		assertThat(first.id()).isEqualTo(1L);
		assertThat(first.email()).isEqualTo("user1@test.com");
		assertThat(first.matchingTicketCount()).isEqualTo(3L);
		assertThat(first.optionTicketCount()).isEqualTo(1L);

		AdminUserSummaryResponse second = result.content().get(1);
		assertThat(second.id()).isEqualTo(2L);
		assertThat(second.matchingTicketCount()).isZero();
		assertThat(second.optionTicketCount()).isZero();
	}

	@Test
	@DisplayName("인벤토리 조회 결과에 없는 사용자는 수량 0으로 채운다")
	void shouldFallbackToEmptyInventoryWhenMissing() {
		// given
		PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));
		Member member = createMemberWithProfile(5L, "user5@test.com", "이영희", "닉네임5", Gender.FEMALE, "https://img5");

		given(memberRepository.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, null, pageable))
			.willReturn(new PageImpl<>(List.of(member), pageable, 1));
		given(itemAdminClient.getInventoryCounts(anyList())).willReturn(Map.of());

		// when
		PagingResponse<AdminUserSummaryResponse> result = adminMemberService.getUsers(null, pageable);

		// then
		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).matchingTicketCount()).isZero();
		assertThat(result.content().get(0).optionTicketCount()).isZero();
	}

	@Test
	@DisplayName("사용자 상세 조회는 기존처럼 단일 사용자 ID로 item-service를 한 번 호출한다")
	void shouldGetUserDetailWithSingleInventoryCall() {
		// given
		Long memberId = 7L;
		Member member = createMemberWithProfile(memberId, "user7@test.com", "상세사용자", "닉네임7", Gender.FEMALE, "https://img7");
		given(memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER))
			.willReturn(Optional.of(member));
		given(itemAdminClient.getInventoryCounts(List.of(memberId)))
			.willReturn(Map.of(memberId, new AdminInventoryCounts(4L, 2L)));

		// when
		var result = adminMemberService.getUserDetail(memberId);

		// then
		assertThat(result.id()).isEqualTo(memberId);
		assertThat(result.matchingTicketCount()).isEqualTo(4L);
		assertThat(result.optionTicketCount()).isEqualTo(2L);
		then(itemAdminClient).should(times(1)).getInventoryCounts(List.of(memberId));
	}

	@Test
	@DisplayName("keyword 앞뒤 공백을 제거해서 저장소로 전달한다")
	void shouldTrimKeywordBeforeQuery() {
		// given
		PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));

		given(memberRepository.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, "nickname", pageable))
			.willReturn(new PageImpl<>(List.of(), pageable, 0));

		// when
		adminMemberService.getUsers("  nickname  ", pageable);

		// then
		then(memberRepository).should()
			.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, "nickname", pageable);
	}

	@Test
	@DisplayName("빈 문자열 keyword는 null로 정규화해서 전달한다")
	void shouldNormalizeBlankKeywordToNull() {
		// given
		PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));

		given(memberRepository.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, null, pageable))
			.willReturn(new PageImpl<>(List.of(), pageable, 0));

		// when
		adminMemberService.getUsers("   ", pageable);

		// then
		then(memberRepository).should()
			.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, null, pageable);
	}

	@Test
	@DisplayName("대상 사용자가 존재하면 인벤토리 조정을 item-service에 위임한다")
	void shouldAdjustInventoryWhenMemberExists() {
		// given
		Long adminId = 900L;
		Long memberId = 15L;
		Member member = createMemberWithProfile(memberId, "u@u.com", "유저", "u", Gender.MALE, "https://img");
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 3, AdminInventoryAction.ADD, "보상 지급"
		);

		given(memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER))
			.willReturn(Optional.of(member));

		// when
		assertThatCode(() -> adminMemberService.updateUserInventory(adminId, memberId, request))
			.doesNotThrowAnyException();

		// then
		then(itemAdminClient).should().adjustInventory(memberId, adminId, request);
	}

	@Test
	@DisplayName("대상 사용자가 없으면 item-service를 호출하지 않고 예외를 던진다")
	void shouldThrowWhenTargetMemberNotFound() {
		// given
		Long adminId = 900L;
		Long memberId = 99L;
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 3, AdminInventoryAction.ADD, "보상 지급"
		);

		given(memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER))
			.willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> adminMemberService.updateUserInventory(adminId, memberId, request))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(UserErrorCode.TARGET_USER_NOT_FOUND);
		then(itemAdminClient).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("item-service가 409를 반환하면 중복 조정 예외로 변환한다")
	void shouldThrowDuplicateWhenItemServiceReturns409() {
		// given
		Long adminId = 900L;
		Long memberId = 15L;
		Member member = createMemberWithProfile(memberId, "u@u.com", "유저", "u", Gender.MALE, "https://img");
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 3, AdminInventoryAction.ADD, "보상 지급"
		);

		given(memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER))
			.willReturn(Optional.of(member));
		willThrow(feignException(409)).given(itemAdminClient).adjustInventory(memberId, adminId, request);

		// when & then
		assertThatThrownBy(() -> adminMemberService.updateUserInventory(adminId, memberId, request))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(UserErrorCode.DUPLICATE_ADMIN_INVENTORY_ADJUSTMENT);
	}

	@Test
	@DisplayName("item-service가 400을 반환하면 아이템 부족 예외로 변환한다")
	void shouldThrowNotEnoughItemWhenItemServiceReturns400() {
		// given
		Long adminId = 900L;
		Long memberId = 15L;
		Member member = createMemberWithProfile(memberId, "u@u.com", "유저", "u", Gender.MALE, "https://img");
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 3, AdminInventoryAction.REMOVE, "오지급 회수"
		);

		given(memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER))
			.willReturn(Optional.of(member));
		willThrow(feignException(400)).given(itemAdminClient).adjustInventory(memberId, adminId, request);

		// when & then
		assertThatThrownBy(() -> adminMemberService.updateUserInventory(adminId, memberId, request))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(UserErrorCode.NOT_ENOUGH_ITEM);
	}

	@Test
	@DisplayName("item-service가 그 외 상태코드를 반환하면 조회 실패 예외로 변환한다")
	void shouldThrowUserQueryFailedForOtherStatus() {
		// given
		Long adminId = 900L;
		Long memberId = 15L;
		Member member = createMemberWithProfile(memberId, "u@u.com", "유저", "u", Gender.MALE, "https://img");
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 3, AdminInventoryAction.ADD, "보상 지급"
		);

		given(memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER))
			.willReturn(Optional.of(member));
		willThrow(feignException(500)).given(itemAdminClient).adjustInventory(memberId, adminId, request);

		// when & then
		assertThatThrownBy(() -> adminMemberService.updateUserInventory(adminId, memberId, request))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(UserErrorCode.USER_QUERY_FAILED);
	}

	@Test
	@DisplayName("item-service 응답 디코딩에 실패하면 조회 실패 예외로 변환한다")
	void shouldThrowUserQueryFailedWhenDecodeFails() {
		// given
		Long adminId = 900L;
		Long memberId = 15L;
		Member member = createMemberWithProfile(memberId, "u@u.com", "유저", "u", Gender.MALE, "https://img");
		AdminInventoryUpdateRequest request = new AdminInventoryUpdateRequest(
			ItemType.MATCHING_TICKET, 3, AdminInventoryAction.ADD, "보상 지급"
		);

		given(memberRepository.findAdminMemberById(memberId, MemberStatus.ACTIVE, MemberRole.ROLE_USER))
			.willReturn(Optional.of(member));
		willThrow(decodeException()).given(itemAdminClient).adjustInventory(memberId, adminId, request);

		// when & then
		assertThatThrownBy(() -> adminMemberService.updateUserInventory(adminId, memberId, request))
			.isInstanceOf(BusinessException.class)
			.extracting("errorCode")
			.isEqualTo(UserErrorCode.USER_QUERY_FAILED);
	}

	private static FeignException feignException(int status) {
		Response response = Response.builder()
			.status(status)
			.reason("error")
			.request(testRequest())
			.headers(Map.of())
			.build();
		return FeignException.errorStatus("ItemAdminClient#adjustInventory", response);
	}

	private static DecodeException decodeException() {
		return new DecodeException(200, "decode failed", testRequest());
	}

	private static Request testRequest() {
		return Request.create(
			Request.HttpMethod.PATCH, "/api/internal/admin/items/1", Map.of(), Request.Body.empty(), null
		);
	}

	private void assertInventoryBatching(int userCount, int expectedCalls, List<Integer> expectedBatchSizes) {
		PageRequest pageable = PageRequest.of(0, userCount, Sort.by(Sort.Direction.DESC, "id"));
		List<Member> members = LongStream.rangeClosed(1, userCount)
			.mapToObj(id -> createMemberWithProfile(
				id,
				"user" + id + "@test.com",
				"사용자" + id,
				"닉네임" + id,
				Gender.MALE,
				"https://img" + id
			))
			.toList();
		List<List<Long>> receivedBatches = new ArrayList<>();

		given(memberRepository.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, null, pageable))
			.willReturn(new PageImpl<>(members, pageable, userCount));
		given(itemAdminClient.getInventoryCounts(anyList())).willAnswer(invocation -> {
			List<Long> memberIds = List.copyOf(invocation.getArgument(0));
			receivedBatches.add(memberIds);
			return memberIds.stream().collect(java.util.stream.Collectors.toMap(
				id -> id,
				id -> new AdminInventoryCounts(id, id + 1)
			));
		});

		PagingResponse<AdminUserSummaryResponse> result = adminMemberService.getUsers(null, pageable);

		then(itemAdminClient).should(times(expectedCalls)).getInventoryCounts(anyList());
		assertThat(receivedBatches).extracting(List::size).containsExactlyElementsOf(expectedBatchSizes);
		assertThat(receivedBatches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(100));
		assertThat(receivedBatches).flatExtracting(batch -> batch)
			.containsExactlyElementsOf(LongStream.rangeClosed(1, userCount).boxed().toList());
		assertThat(result.content()).hasSize(userCount).allSatisfy(summary -> {
			assertThat(summary.matchingTicketCount()).isEqualTo(summary.id());
			assertThat(summary.optionTicketCount()).isEqualTo(summary.id() + 1);
		});
	}

	private static Member createMemberWithProfile(
		Long id,
		String email,
		String realName,
		String nickname,
		Gender gender,
		String imageUrl
	) {
		Member member = Member.builder()
			.email(email)
			.role(MemberRole.ROLE_USER)
			.status(MemberStatus.ACTIVE)
			.build();
		member.updateRealName(realName);

		Profile profile = Profile.builder()
			.member(member)
			.nickname(nickname)
			.gender(gender)
			.profileImageUrl(imageUrl)
			.build();

		member.setProfile(profile);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
