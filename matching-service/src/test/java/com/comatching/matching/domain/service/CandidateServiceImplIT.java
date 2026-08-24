package com.comatching.matching.domain.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
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
@Import(CandidateServiceImpl.class)
@DisplayName("CandidateService 탈퇴-갱신 순서 역전 가드")
class CandidateServiceImplIT extends MySqlContainerSupport {

	private static final Long MEMBER_ID = 777L;

	@Autowired
	private CandidateService candidateService;

	@Autowired
	private MatchingCandidateRepository candidateRepository;

	@Autowired
	private WithdrawnMemberRepository withdrawnMemberRepository;

	@Test
	@DisplayName("정상 순서: 갱신 후 탈퇴가 오면 후보가 삭제되고 tombstone 이 남는다")
	void withdrawAfterUpdate() {
		candidateService.upsertCandidate(profileEvent(1L));

		candidateService.removeCandidate(MEMBER_ID, LocalDateTime.now());

		assertThat(candidateRepository.existsByMemberId(MEMBER_ID)).isFalse();
		assertThat(withdrawnMemberRepository.existsById(MEMBER_ID)).isTrue();
	}

	@Test
	@DisplayName("역전 순서: 탈퇴 처리 뒤에 도착한 갱신 이벤트는 후보를 부활시키지 못한다")
	void staleUpdateAfterWithdrawDoesNotResurrect() {
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
		candidateService.upsertCandidate(profileEvent(1L));

		MatchingCandidate created = candidateRepository.findById(MEMBER_ID).orElseThrow();
		assertThat(created.getProfileId()).isEqualTo(1L);

		candidateService.upsertCandidate(profileEvent(2L));

		MatchingCandidate updated = candidateRepository.findById(MEMBER_ID).orElseThrow();
		assertThat(updated.getProfileId()).isEqualTo(2L);
	}

	private ProfileUpdatedMatchingEvent profileEvent(Long profileId) {
		return ProfileUpdatedMatchingEvent.builder()
			.memberId(MEMBER_ID)
			.profileId(profileId)
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
