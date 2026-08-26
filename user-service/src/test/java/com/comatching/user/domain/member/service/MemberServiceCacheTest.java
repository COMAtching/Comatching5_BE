package com.comatching.user.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.global.config.CacheConfig;
import com.comatching.user.global.config.ProfileImageProperties;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MemberServiceCacheTest.TestConfig.class)
@DisplayName("참여자 수 캐시 테스트")
class MemberServiceCacheTest {

	@Autowired
	private MemberService memberService;

	@Autowired
	private MemberRepository memberRepository;

	@Test
	@DisplayName("TTL 안에서는 반복 조회해도 COUNT 쿼리는 한 번만 나간다")
	void shouldHitDatabaseOnlyOnceWithinTtl() {
		// given
		given(memberRepository.countByRoleAndStatus(MemberRole.ROLE_USER, MemberStatus.ACTIVE)).willReturn(50000L);

		// when
		long first = memberService.getActiveUserCount();
		long second = memberService.getActiveUserCount();
		long third = memberService.getActiveUserCount();

		// then
		assertThat(first).isEqualTo(50000L);
		assertThat(second).isEqualTo(50000L);
		assertThat(third).isEqualTo(50000L);
		then(memberRepository).should(times(1)).countByRoleAndStatus(MemberRole.ROLE_USER, MemberStatus.ACTIVE);
	}

	@Configuration
	@Import({CacheConfig.class, MemberServiceImpl.class})
	static class TestConfig {

		@Bean
		MemberRepository memberRepository() {
			return mock(MemberRepository.class);
		}

		@Bean
		UserEventPublisher userEventPublisher() {
			return mock(UserEventPublisher.class);
		}

		@Bean
		ProfileImageProperties profileImageProperties() {
			return mock(ProfileImageProperties.class);
		}
	}
}
