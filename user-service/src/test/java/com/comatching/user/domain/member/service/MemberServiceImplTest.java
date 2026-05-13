package com.comatching.user.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.domain.enums.SocialType;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberServiceImpl 테스트")
class MemberServiceImplTest {

	@InjectMocks
	private MemberServiceImpl memberService;

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private UserEventPublisher eventPublisher;

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
	@DisplayName("사용자 실명을 조회한다")
	void shouldReturnRealName() {
		// given
		Member member = Member.builder()
			.email("user@test.com")
			.password("password")
			.socialType(SocialType.KAKAO)
			.socialId("12345")
			.role(MemberRole.ROLE_USER)
			.status(MemberStatus.ACTIVE)
			.build();
		member.updateRealName("홍길동");
		given(memberRepository.findById(805L)).willReturn(Optional.of(member));

		// when
		String result = memberService.getRealName(805L);

		// then
		assertThat(result).isEqualTo("홍길동");
	}

	@Test
	@DisplayName("회원 탈퇴 이벤트는 트랜잭션 커밋 이후 발행한다")
	void shouldPublishWithdrawEventAfterCommit() {
		// given
		Member member = Member.builder()
			.email("user@test.com")
			.password("password")
			.socialType(SocialType.KAKAO)
			.socialId("12345")
			.role(MemberRole.ROLE_USER)
			.status(MemberStatus.ACTIVE)
			.build();
		given(memberRepository.findById(805L)).willReturn(Optional.of(member));

		TransactionSynchronizationManager.initSynchronization();
		try {
			// when
			memberService.withdrawMember(805L);

			// then
			assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
			verifyNoInteractions(eventPublisher);

			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
			verify(eventPublisher).sendWithdrawEvent(805L, "user@test.com");
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}
}
