package com.comatching.user.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.member.AdminUserProfileDto;
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
		Member member = createMemberWithProfile(1L, "user@test.com", "닉네임", Gender.FEMALE, "https://img");
		given(memberRepository.searchMembersForAdmin(MemberStatus.ACTIVE, MemberRole.ROLE_USER, "user"))
			.willReturn(List.of(member));

		// when
		List<AdminUserProfileDto> result = adminMemberQueryService.getUsers("user");

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(1L);
		assertThat(result.get(0).email()).isEqualTo("user@test.com");
		assertThat(result.get(0).nickname()).isEqualTo("닉네임");
		assertThat(result.get(0).gender()).isEqualTo(Gender.FEMALE);
		assertThat(result.get(0).profileImageUrl()).isEqualTo("https://img");
	}

	@Test
	@DisplayName("관리자 사용자 상세를 조회한다")
	void shouldReturnAdminUserDetail() {
		// given
		Member member = createMemberWithProfile(3L, "detail@test.com", "상세유저", Gender.MALE, "https://detail");
		given(memberRepository.findAdminMemberById(3L, MemberStatus.ACTIVE, MemberRole.ROLE_USER))
			.willReturn(Optional.of(member));

		// when
		AdminUserProfileDto result = adminMemberQueryService.getUserDetail(3L);

		// then
		assertThat(result.id()).isEqualTo(3L);
		assertThat(result.email()).isEqualTo("detail@test.com");
		assertThat(result.nickname()).isEqualTo("상세유저");
	}

	private static Member createMemberWithProfile(Long id, String email, String nickname, Gender gender, String imageUrl) {
		Member member = Member.builder()
			.email(email)
			.role(MemberRole.ROLE_USER)
			.status(MemberStatus.ACTIVE)
			.build();

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
