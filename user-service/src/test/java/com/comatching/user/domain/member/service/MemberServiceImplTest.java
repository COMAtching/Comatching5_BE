package com.comatching.user.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDate;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import com.comatching.user.domain.auth.service.LoginSessionStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.domain.enums.SocialType;
import com.comatching.common.dto.auth.SocialLoginRequestDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.operations.Feature;
import com.comatching.common.operations.FeatureGate;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.global.config.ProfileImageProperties;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberServiceImpl 테스트")
class MemberServiceImplTest {

	@InjectMocks
	private MemberServiceImpl memberService;

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private FeatureGate features;

	@Test
	void firstKakaoLoginDoesNotCreateAnAccountWhenLoginIsClosed() {
		var request = new SocialLoginRequestDto("guest@example.invalid", SocialType.KAKAO, "first-login");
		org.mockito.BDDMockito.willThrow(new BusinessException(GeneralErrorCode.FEATURE_CLOSED))
			.given(features).requireOpen(Feature.LOGIN);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> memberService.socialLogin(request)).isInstanceOf(BusinessException.class);
		then(memberRepository).should(org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Mock
	private UserEventPublisher eventPublisher;

	@Mock
	private LoginSessionStore sessions;

	@Mock
	private EntityManager entityManager;

	@Mock
	private ProfileImageProperties profileImageProperties;

	@Test
	void notificationEmailIsAvailableOnlyWhileTheRecipientExists() {
		Member member = Member.builder().email("recipient@example.invalid").status(MemberStatus.ACTIVE).build();
		given(memberRepository.findById(100L)).willReturn(Optional.of(member));
		assertThat(memberService.findNotificationEmail(100L)).contains("recipient@example.invalid");
		member.withdraw();
		assertThat(memberService.findNotificationEmail(100L)).isEmpty();
		assertThat(memberService.findNotificationEmail(101L)).isEmpty();
	}

	@Test
	@DisplayName("활성 사용자 수를 조회한다")
	void shouldReturnActiveUserCount() {
		// given
		given(memberRepository.countByRoleAndStatus(MemberRole.ROLE_USER, MemberStatus.ACTIVE)).willReturn(321L);

		// when
		long result = memberService.getActiveUserCount();

		// then
		assertThat(result).isEqualTo(321L);
	}

	@Test
	@DisplayName("회원 실명을 조회한다")
	void shouldReturnRealName() {
		// given
		Member member = Member.builder()
			.email("user@example.com")
			.password("encoded")
			.role(MemberRole.ROLE_USER)
			.status(MemberStatus.ACTIVE)
			.build();
		member.updateRealName("홍길동");
		given(memberRepository.findById(100L)).willReturn(Optional.of(member));

		// when
		String result = memberService.getRealName(100L);

		// then
		assertThat(result).isEqualTo("홍길동");
	}

	@Test
	@DisplayName("회원 탈퇴 시 프로필 non-null 필드를 안전한 값으로 유지하고 매칭 가능 상태를 끈다")
	void shouldMaskProfileWithoutNullingRequiredFieldsOnWithdraw() {
		// given
		Member member = Member.builder()
			.email("user@example.com")
			.password("encoded")
			.role(MemberRole.ROLE_USER)
			.status(MemberStatus.ACTIVE)
			.build();
		ReflectionTestUtils.setField(member, "id", 100L);
		Profile profile = Profile.builder()
			.member(member)
			.nickname("닉네임")
			.gender(Gender.MALE)
			.birthDate(LocalDate.of(2000, 1, 1))
			.intro("소개")
			.mbti("ENTP")
			.university("학교")
			.major("전공")
			.contactFrequency(ContactFrequency.NORMAL)
			.profileImageUrl("https://s3.com/profiles/100/custom.png")
			.build();
		member.setProfile(profile);
		given(entityManager.getReference(Member.class, 100L)).willReturn(member);
		given(profileImageProperties.baseUrl()).willReturn("https://srv.comatching.site/api/public/profile-images");

		// when
		memberService.withdrawMember(100L);

		// then
		assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
		assertThat(profile.getBirthDate()).isEqualTo(LocalDate.of(1970, 1, 1));
		assertThat(profile.getMbti()).isEqualTo("UNKNOWN");
		assertThat(profile.getUniversity()).isEqualTo("(알 수 없음)");
		assertThat(profile.getMajor()).isEqualTo("(알 수 없음)");
		assertThat(profile.getProfileImageUrl())
			.isEqualTo("https://srv.comatching.site/api/public/profile-images/default.png");
		assertThat(profile.isMatchable()).isFalse();
		assertThat(profile.getHobbies()).isEmpty();
		assertThat(profile.getTags()).isEmpty();
		assertThat(member.getAuthVersion()).isOne();
		then(sessions).should().endAll(100L);
		then(eventPublisher).should().sendWithdrawEvent(100L, "user@example.com");
	}
}
