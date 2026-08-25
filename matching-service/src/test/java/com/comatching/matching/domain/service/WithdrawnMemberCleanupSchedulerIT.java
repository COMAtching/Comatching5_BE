package com.comatching.matching.domain.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import com.comatching.matching.domain.entity.WithdrawnMember;
import com.comatching.matching.domain.repository.candidate.WithdrawnMemberRepository;
import com.comatching.matching.support.MySqlContainerSupport;

/**
 * tombstone TTL 정리 검증.
 *
 * 잘못 지우면 두 방향으로 사고가 난다: TTL 이전 행을 지우면 늦은 갱신
 * 이벤트가 탈퇴 회원을 부활시키고, TTL 경과 행을 남기면 같은 memberId 로
 * 재가입한 회원이 영원히 매칭에서 제외된다. 두 방향을 모두 확인한다.
 * 벌크 JPQL 삭제는 방언을 타므로 다른 IT 와 같은 실제 MySQL 로 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = WithdrawnMemberCleanupSchedulerIT.Config.class)
@Import(WithdrawnMemberCleanupScheduler.class)
@DisplayName("탈퇴 tombstone TTL 정리 배치")
class WithdrawnMemberCleanupSchedulerIT extends MySqlContainerSupport {

	private static final long RETENTION_DAYS = 14;

	@Autowired
	private WithdrawnMemberCleanupScheduler scheduler;

	@Autowired
	private WithdrawnMemberRepository withdrawnMemberRepository;

	@Test
	@DisplayName("TTL 이 지난 tombstone 만 지우고 보존 기간 내 tombstone 은 남긴다")
	void purgeOnlyExpiredTombstones() {
		LocalDateTime now = LocalDateTime.now();
		withdrawnMemberRepository.saveAndFlush(
			WithdrawnMember.of(1L, now.minusDays(RETENTION_DAYS + 1)));
		withdrawnMemberRepository.saveAndFlush(
			WithdrawnMember.of(2L, now.minusDays(RETENTION_DAYS - 1)));
		withdrawnMemberRepository.saveAndFlush(
			WithdrawnMember.of(3L, now.minusHours(1)));

		scheduler.purgeExpiredTombstones();

		assertThat(withdrawnMemberRepository.existsById(1L)).isFalse();
		assertThat(withdrawnMemberRepository.existsById(2L)).isTrue();
		assertThat(withdrawnMemberRepository.existsById(3L)).isTrue();
	}

	@Test
	@DisplayName("지울 것이 없으면 아무 행도 건드리지 않는다")
	void noopWhenNothingExpired() {
		withdrawnMemberRepository.saveAndFlush(
			WithdrawnMember.of(4L, LocalDateTime.now().minusDays(1)));

		scheduler.purgeExpiredTombstones();

		assertThat(withdrawnMemberRepository.existsById(4L)).isTrue();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = WithdrawnMember.class)
	@EnableJpaRepositories(basePackageClasses = WithdrawnMemberRepository.class)
	static class Config {
	}
}
