package com.comatching.user.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMemberQueryServiceImpl 테스트")
class AdminMemberQueryServiceImplTest {

	@InjectMocks
	private AdminMemberQueryServiceImpl adminMemberQueryService;

	@Mock
	private MemberRepository memberRepository;

	@Test
	@DisplayName("키워드로 관리자 사용자 목록을 조회한다")
	void shouldReturnAdminUserList() {
		// given
		PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));
		Member member = createMemberWithProfile(
			1L,
			"user@test.com",
			"홍길동",
			"닉네임",
			Gender.FEMALE,
			"https://img"
		);
		given(memberRepository.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, "user", pageable))
			.willReturn(new PageImpl<>(List.of(member), pageable, 1));

		// when
		PagingResponse<AdminUserProfileDto> result = adminMemberQueryService.getUsers("user", pageable);

		// then
		assertThat(result.content()).hasSize(1);
		assertThat(result.currentPage()).isZero();
		assertThat(result.size()).isEqualTo(20);
		assertThat(result.totalElements()).isEqualTo(1);
		assertThat(result.content().get(0).id()).isEqualTo(1L);
		assertThat(result.content().get(0).email()).isEqualTo("user@test.com");
		assertThat(result.content().get(0).realName()).isEqualTo("홍길동");
		assertThat(result.content().get(0).nickname()).isEqualTo("닉네임");
		assertThat(result.content().get(0).gender()).isEqualTo(Gender.FEMALE);
		assertThat(result.content().get(0).profileImageUrl()).isEqualTo("https://img");
	}

	@Test
	@DisplayName("관리자 사용자 상세를 조회한다")
	void shouldReturnAdminUserDetail() {
		// given
		Member member = createMemberWithProfile(
			3L,
			"detail@test.com",
			"김상세",
			"상세유저",
			Gender.MALE,
			"https://detail"
		);
		given(memberRepository.findAdminMemberById(3L, MemberStatus.ACTIVE, MemberRole.ROLE_USER))
			.willReturn(Optional.of(member));

		// when
		AdminUserProfileDto result = adminMemberQueryService.getUserDetail(3L);

		// then
		assertThat(result.id()).isEqualTo(3L);
		assertThat(result.email()).isEqualTo("detail@test.com");
		assertThat(result.realName()).isEqualTo("김상세");
		assertThat(result.nickname()).isEqualTo("상세유저");
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
		org.springframework.test.util.ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
