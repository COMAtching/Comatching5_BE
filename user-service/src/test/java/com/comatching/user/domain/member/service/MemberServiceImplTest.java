package com.comatching.user.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.user.domain.event.UserEventPublisher;
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
}
