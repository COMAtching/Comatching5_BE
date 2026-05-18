package com.comatching.matching.domain.repository.history;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import com.comatching.matching.domain.entity.MatchingHistory;

@DataJpaTest(properties = {
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = MatchingHistoryRepositoryTest.RepositoryTestConfig.class)
@DisplayName("MatchingHistoryRepository 테스트")
class MatchingHistoryRepositoryTest {

	@Autowired
	private MatchingHistoryRepository matchingHistoryRepository;

	@Test
	@DisplayName("양방향 매칭 이력이 있는 상대 회원 ID를 조회한다")
	void shouldFindMatchedMemberIdsInBothDirections() {
		// given
		matchingHistoryRepository.save(MatchingHistory.builder()
			.memberId(1L)
			.partnerId(2L)
			.build());
		matchingHistoryRepository.save(MatchingHistory.builder()
			.memberId(3L)
			.partnerId(1L)
			.build());
		matchingHistoryRepository.save(MatchingHistory.builder()
			.memberId(4L)
			.partnerId(5L)
			.build());
		matchingHistoryRepository.flush();

		// when
		List<Long> matchedMemberIds = matchingHistoryRepository.findMatchedMemberIdsByMemberId(1L);

		// then
		assertThat(matchedMemberIds).containsExactlyInAnyOrder(2L, 3L);
	}

	@Test
	@DisplayName("정규화된 pairKey unique 제약으로 반대 방향 중복 히스토리를 막는다")
	void shouldRejectReverseDuplicateByPairKey() {
		// given
		MatchingHistory first = MatchingHistory.builder()
			.memberId(1L)
			.partnerId(2L)
			.build();
		matchingHistoryRepository.saveAndFlush(first);

		// when & then
		assertThatThrownBy(() -> matchingHistoryRepository.saveAndFlush(MatchingHistory.builder()
			.memberId(2L)
			.partnerId(1L)
			.build()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = MatchingHistory.class)
	@EnableJpaRepositories(basePackageClasses = MatchingHistoryRepository.class)
	static class RepositoryTestConfig {
	}
}
