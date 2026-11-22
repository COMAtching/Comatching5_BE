package com.comatching.matching.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.comatching.matching.infra.client.MemberClient;
import com.comatching.common.dto.event.matching.MatchingProfileSnapshot;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
import com.comatching.matching.domain.repository.candidate.WithdrawnMemberRepository;
import com.comatching.matching.support.MySqlContainerSupport;

/**
 * 탈퇴 tombstone 가드 검증.
 *
 * member-withdraw 와 profile-updates 는 서로 다른 토픽이라 메시지 키를 걸어도
 * 둘 사이의 도착 순서는 보장되지 않는다. 이 테스트는 그 역전 시나리오
 * (탈퇴 처리 후 갱신 이벤트 도착)에서 후보가 부활하지 않는 것을 실제 MySQL 로
 * 확인한다. upsert 의 tombstone 조회가 잠금 조회(PESSIMISTIC_WRITE)라
 * 방언 중립적이지 않으므로 H2 가 아닌 실제 MySQL 을 쓴다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = CandidateServiceImplIT.Config.class)
@Import({CandidateServiceImpl.class, CandidateProfileStore.class})
@DisplayName("CandidateService 탈퇴-갱신 순서 역전 가드")
@Tag("integration")
class CandidateServiceImplIT extends MySqlContainerSupport {

	private static final Long MEMBER_ID = 777L;

	@MockitoBean
	private MemberClient members;

	@Autowired
	private CandidateService candidateService;

	@Autowired
	private MatchingCandidateRepository candidateRepository;

	@Autowired
	private WithdrawnMemberRepository withdrawnMemberRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	@DisplayName("정상 순서: 갱신 후 탈퇴가 오면 후보가 삭제되고 tombstone 이 남는다")
	void withdrawAfterUpdate() {
		serveCurrent(profileEvent(1L));
		candidateService.upsertCandidate(profileEvent(1L));

		candidateService.removeCandidate(MEMBER_ID, LocalDateTime.now());

		assertThat(candidateRepository.existsByMemberId(MEMBER_ID)).isFalse();
		assertThat(withdrawnMemberRepository.existsById(MEMBER_ID)).isTrue();
	}

	@Test
	@DisplayName("역전 순서: 탈퇴 처리 뒤에 도착한 갱신 이벤트는 후보를 부활시키지 못한다")
	void staleUpdateAfterWithdrawDoesNotResurrect() {
		serveCurrent(profileEvent(1L));
		candidateService.upsertCandidate(profileEvent(1L));
		candidateService.removeCandidate(MEMBER_ID, LocalDateTime.now());

		// 다른 토픽에서 뒤늦게 소비된 갱신 이벤트
		candidateService.upsertCandidate(profileEvent(2L));

		assertThat(candidateRepository.existsByMemberId(MEMBER_ID)).isFalse();
	}

	@Test
	@DisplayName("후보가 만들어진 적 없는 회원의 탈퇴도, 탈퇴 이벤트 재전달도 멱등하다")
	void removeIsIdempotent() {
		candidateService.removeCandidate(MEMBER_ID, LocalDateTime.now());
		candidateService.removeCandidate(MEMBER_ID, LocalDateTime.now());

		assertThat(candidateRepository.existsByMemberId(MEMBER_ID)).isFalse();
		assertThat(withdrawnMemberRepository.existsById(MEMBER_ID)).isTrue();
	}

	@Test
	@DisplayName("탈퇴하지 않은 회원의 upsert 는 그대로 동작한다 (신규 생성 + 갱신)")
	void upsertStillWorksForActiveMember() {
		serveCurrent(profileEvent(1L));
		candidateService.upsertCandidate(profileEvent(1L));

		MatchingCandidate created = candidateRepository.findById(MEMBER_ID).orElseThrow();
		assertThat(created.getProfileId()).isEqualTo(1L);

		candidateService.upsertCandidate(profileEvent(2L));

		MatchingCandidate updated = candidateRepository.findById(MEMBER_ID).orElseThrow();
		assertThat(updated.getProfileId()).isEqualTo(2L);
	}

	/**
	 * 두 리스너 스레드가 같은 회원을 동시에 처리하는 교차를 실제 두 트랜잭션으로
	 * 재현한다. upsert 트랜잭션이 tombstone 잠금 조회(빈 행 → 갭 락)를 잡은 채
	 * 후보를 삽입하는 동안 탈퇴가 끼어들면, 탈퇴의 tombstone INSERT 는 갭 락에
	 * 막혔다가 upsert 커밋 후 진행된다. 이때 후보 삭제가 스냅샷 읽기였다면
	 * 방금 커밋된 후보를 못 보고 건너뛰어 탈퇴 회원이 영구히 매칭 대상으로
	 * 남는다. 잠금 조회(current read) 삭제만 이 창을 닫는다.
	 */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("동시 실행: upsert 진행 중에 탈퇴가 끼어들어도 후보는 남지 않는다")
	void concurrentUpsertAndWithdrawLeavesNoCandidate() throws Exception {
		Long memberId = 888L;
		TransactionTemplate newTx = new TransactionTemplate(transactionManager);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch gapLockAcquired = new CountDownLatch(1);

		try {
			// T1: upsert 트랜잭션의 발자국 — tombstone 잠금 조회 후 후보 삽입.
			// 갭 락을 잡은 채 잠시 머물러 탈퇴가 그 사이에 끼어들 시간을 만든다.
			Future<?> upsertTx = executor.submit(() -> newTx.execute(status -> {
				withdrawnMemberRepository.findWithLockByMemberId(memberId);
				gapLockAcquired.countDown();
				try {
					Thread.sleep(1_000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				candidateRepository.save(MatchingCandidate.create(
					memberId, 1L, Gender.MALE, "ISTJ", "컴퓨터공학과",
					ContactFrequency.FREQUENT, List.of(HobbyCategory.SPORTS),
					LocalDate.of(2000, 1, 1), true));
				return null;
			}));

			assertThat(gapLockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

			// T2: 실제 프로덕션 경로. saveAndFlush 가 T1 의 갭 락에 막혔다가
			// T1 커밋 후 진행되고, 잠금 조회 삭제가 T1 이 만든 후보를 지운다.
			Future<?> withdrawTx = executor.submit(() ->
				candidateService.removeCandidate(memberId, LocalDateTime.now()));

			upsertTx.get(30, TimeUnit.SECONDS);
			withdrawTx.get(30, TimeUnit.SECONDS);

			assertThat(candidateRepository.existsByMemberId(memberId)).isFalse();
			assertThat(withdrawnMemberRepository.existsById(memberId)).isTrue();
		} finally {
			executor.shutdownNow();
			// 이 테스트는 실제 커밋을 남기므로 다른 테스트를 위해 직접 지운다
			newTx.executeWithoutResult(status -> {
				candidateRepository.findById(memberId).ifPresent(candidateRepository::delete);
				withdrawnMemberRepository.findById(memberId).ifPresent(withdrawnMemberRepository::delete);
			});
		}
	}

	@Test
	void staleAndDuplicateProfileEventsKeepTheLatestSnapshot() {
		serveCurrent(versionedMajor(3L, "의학과"));
		candidateService.upsertCandidate(versionedMajor(3L, "의학과"));
		candidateService.upsertCandidate(versionedMajor(2L, "컴퓨터공학과"));
		candidateService.upsertCandidate(versionedMajor(3L, "동일 버전에서 덮어쓰기 시도"));
		var candidate = candidateRepository.findById(MEMBER_ID).orElseThrow();
		assertThat(candidate.getLastProfileVersion()).isEqualTo(3L);
		assertThat(candidate.getMajor()).isEqualTo("의학과");
		verify(members).getMatchingSnapshot(MEMBER_ID);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentOriginalAndReplayKeepHighestVersion() throws Exception {
		serveCurrent(versionedMajor(1L, "최초 학과"));
		candidateService.upsertCandidate(versionedMajor(1L, "최초 학과"));
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var original = pool.submit(() -> {
				start.await();
				candidateService.upsertCandidate(versionedMajor(3L, "의학과"));
				return null;
			});
			var replay = pool.submit(() -> {
				start.await();
				candidateService.upsertCandidate(versionedMajor(2L, "이전 학과"));
				return null;
			});
			start.countDown();
			original.get(15, TimeUnit.SECONDS);
			replay.get(15, TimeUnit.SECONDS);
			var candidate = candidateRepository.findById(MEMBER_ID).orElseThrow();
			assertThat(candidate.getLastProfileVersion()).isEqualTo(3L);
			assertThat(candidate.getMajor()).isEqualTo("의학과");
		} finally {
			pool.shutdownNow();
			candidateRepository.deleteById(MEMBER_ID);
		}
	}

	@Test
	void legacyReplayRepairsFromMemberServiceEvenBeforeFirstVersionedEdit() {
		serveCurrent(versionedMajor(0L, "현재 학과"));
		candidateService.upsertCandidate(versionedMajor(null, "옛 학과"));
		assertThat(candidateRepository.findById(MEMBER_ID).orElseThrow().getMajor()).isEqualTo("현재 학과");
		serveCurrent(versionedMajor(5L, "최신 학과"));
		candidateService.upsertCandidate(versionedMajor(2L, "중간 학과"));
		assertThat(candidateRepository.findById(MEMBER_ID).orElseThrow().getLastProfileVersion()).isEqualTo(5L);
		serveCurrent(versionedMajor(4L, "조회 후 도착이 늦은 응답"));
		candidateService.upsertCandidate(versionedMajor(null, "옛 학과"));
		assertThat(candidateRepository.findById(MEMBER_ID).orElseThrow().getMajor()).isEqualTo("최신 학과");
	}

	@Test
	void expiredTombstoneCannotResurrectWithdrawnMember() {
		var withdrawnAt = LocalDateTime.now().minusDays(4);
		candidateService.removeCandidate(MEMBER_ID, withdrawnAt);
		assertThat(withdrawnMemberRepository.deleteAllByWithdrawnAtBefore(LocalDateTime.now().minusDays(2))).isOne();
		when(members.getMatchingSnapshot(MEMBER_ID)).thenReturn(new MatchingProfileSnapshot(MEMBER_ID,
			MatchingProfileSnapshot.State.WITHDRAWN, null, withdrawnAt));
		candidateService.upsertCandidate(versionedMajor(3L, "탈퇴 전"));
		assertThat(candidateRepository.findById(MEMBER_ID)).isEmpty();
		assertThat(withdrawnMemberRepository.existsById(MEMBER_ID)).isTrue();
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void withdrawDuringRemoteLookupWins() {
		var current = versionedMajor(3L, "조회 당시 학과");
		when(members.getMatchingSnapshot(MEMBER_ID)).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			candidateService.removeCandidate(MEMBER_ID, LocalDateTime.now());
			return active(current);
		});
		try {
			candidateService.upsertCandidate(versionedMajor(1L, "옛 학과"));
			assertThat(candidateRepository.findById(MEMBER_ID)).isEmpty();
		} finally {
			withdrawnMemberRepository.deleteById(MEMBER_ID);
		}
	}

	@Test
	void lookupFailureOrLagNeverUsesOldPayloadAsFallback() {
		var replay = versionedMajor(3L, "옛 학과");
		when(members.getMatchingSnapshot(MEMBER_ID))
			.thenThrow(new IllegalStateException("user-service unavailable"))
			.thenReturn(active(versionedMajor(2L, "지연된 응답")))
			.thenReturn(new MatchingProfileSnapshot(MEMBER_ID, MatchingProfileSnapshot.State.NO_ACTIVE_PROFILE, null, null));
		assertThatThrownBy(() -> candidateService.upsertCandidate(replay)).hasMessageContaining("unavailable");
		assertThatThrownBy(() -> candidateService.upsertCandidate(replay)).hasMessageContaining("버전");
		candidateService.upsertCandidate(replay);
		assertThat(candidateRepository.findById(MEMBER_ID)).isEmpty();
	}

	private void serveCurrent(ProfileUpdatedMatchingEvent profile) {
		when(members.getMatchingSnapshot(MEMBER_ID)).thenReturn(active(profile));
	}

	private MatchingProfileSnapshot active(ProfileUpdatedMatchingEvent profile) {
		return new MatchingProfileSnapshot(MEMBER_ID, MatchingProfileSnapshot.State.ACTIVE, profile, null);
	}

	private ProfileUpdatedMatchingEvent versionedMajor(Long version, String major) {
		return ProfileUpdatedMatchingEvent.builder()
			.memberId(MEMBER_ID).profileId(10L).profileVersion(version)
			.major(major).isMatchable(true).build();
	}

	private ProfileUpdatedMatchingEvent profileEvent(Long profileId) {
		return ProfileUpdatedMatchingEvent.builder()
			.memberId(MEMBER_ID)
			.profileId(profileId)
			.profileVersion(profileId)
			.gender(Gender.MALE)
			.mbti("ISTJ")
			.major("컴퓨터공학과")
			.contactFrequency(ContactFrequency.FREQUENT)
			.hobbyCategories(List.of(HobbyCategory.SPORTS))
			.birthDate(LocalDate.of(2000, 1, 1))
			.isMatchable(true)
			.build();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = MatchingCandidate.class)
	@EnableJpaRepositories(basePackageClasses = MatchingCandidateRepository.class)
	static class Config {
	}
}
