package com.comatching.user.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Map;

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
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.user.domain.admin.dto.AdminInventoryCounts;
import com.comatching.user.domain.admin.dto.AdminUserSummaryResponse;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.infra.client.ItemAdminClient;

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
