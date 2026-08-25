package com.comatching.matching.domain.repository.candidate;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.enums.AgeOption;
import com.comatching.matching.support.MySqlContainerSupport;

import jakarta.persistence.EntityManager;

/**
 * 후보 조회 SQL 검증.
 *
 * 개선 A/B 로 점수 계산·필터링·표본 추출이 전부 SQL 로 내려갔다.
 * 그래서 이 로직은 목(mock)으로 검증할 수 없고 실제 MySQL 이 있어야 한다.
 * 자바에 남은 조건 조립 로직은 MatchingProcessorTest 가 따로 본다.
 *
 * 기준 나이는 24. myAge 를 24 로 고정하고 후보 나이를 조절해 점수를 만든다.
 * MBTI "XXXX" 는 어떤 요청 글자와도 겹치지 않게 하려는 장치다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MatchingCandidateRepositoryIT.Config.class)
@DisplayName("MatchingCandidateRepository 통합 테스트")
class MatchingCandidateRepositoryIT extends MySqlContainerSupport {

	private static final int MY_AGE = 24;
	private static final int FULL_SAMPLE = 5_000;

	@Autowired
	private MatchingCandidateRepository repository;

	@Autowired
	private EntityManager em;

	// ================= 점수 =================

	@Nested
	@DisplayName("점수 계산")
	class Scoring {

		@Test
		@DisplayName("총점이 가장 높은 후보를 반환한다")
		void returnsHighestTotalScore() {
			// MBTI 4글자 전부(40) + 나이 일치(20) + 연락빈도 일치(10) = 70
			save(1L, "ENFP", MY_AGE, ContactFrequency.NORMAL, List.of());
			// MBTI 2글자(20) + 나이 일치(20) = 40
			save(2L, "ENTJ", MY_AGE, ContactFrequency.RARE, List.of());
			// MBTI 4글자(40)만 = 40
			save(3L, "ENFP", MY_AGE + 3, ContactFrequency.RARE, List.of());

			assertThat(findBest(fullScore().build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(1L);
		}

		@Test
		@DisplayName("같은 취미 카테고리를 3개 이상 가지면 20점, 2개면 15점, 1개면 10점이다")
		void hobbyScoreCountsDuplicates() {
			// 이 규칙 때문에 비트마스크 비정규화를 접었다. 유무가 아니라 '개수'가 점수를 가른다.
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of(HobbyCategory.GAME));
			save(2L, "XXXX", 99, ContactFrequency.RARE, List.of(HobbyCategory.GAME, HobbyCategory.GAME));
			save(3L, "XXXX", 99, ContactFrequency.RARE,
				List.of(HobbyCategory.GAME, HobbyCategory.GAME, HobbyCategory.GAME));

			// MBTI·나이·연락빈도 점수는 켜지 않아 취미 점수만 남는다
			assertThat(findBest(base().scoreHobbyCategory(HobbyCategory.GAME).build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(3L);
		}

		@Test
		@DisplayName("해당 취미가 없으면 0점이다")
		void hobbyScoreZeroWhenAbsent() {
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of(HobbyCategory.MUSIC));
			save(2L, "XXXX", 99, ContactFrequency.RARE, List.of(HobbyCategory.GAME));

			assertThat(findBest(base().scoreHobbyCategory(HobbyCategory.GAME).build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("나이 옵션 OLDER 는 나보다 많은 후보에게만 점수를 준다")
		void ageScoreRespectsOption() {
			save(1L, "XXXX", MY_AGE - 1, ContactFrequency.RARE, List.of());
			save(2L, "XXXX", MY_AGE + 1, ContactFrequency.RARE, List.of());

			assertThat(findBest(base().myAge(MY_AGE).scoreAgeOption(AgeOption.OLDER).build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("점수 항이 하나도 없어도 예외 없이 한 명을 반환한다")
		void noScoreTermsStillReturnsCandidate() {
			// 점수 항이 하나도 없는 경로. 정렬식에 상수 "0" 을 넣으면 MySQL 이
			// ORDER BY 의 정수 리터럴을 '몇 번째 컬럼'으로 읽어 죽는다(괄호로 감싸도 같다).
			// 그래서 이때는 점수를 빼고 RAND() 만 남긴다. 이 테스트가 그 회귀를 막는다.
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of());

			assertThat(findBest(base().build())).isPresent();
		}
	}

	// ================= 필수 조건 =================

	@Nested
	@DisplayName("필수 조건")
	class Filtering {

		@Test
		@DisplayName("반대 성별만 조회한다")
		void filtersByGender() {
			save(1L, Gender.FEMALE, "XXXX", 99, ContactFrequency.RARE, null, true, List.of());
			save(2L, Gender.MALE, "XXXX", 99, ContactFrequency.RARE, null, true, List.of());

			assertThat(findBest(base().targetGender(Gender.MALE).build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("매칭을 끈 후보는 제외한다")
		void excludesNotMatchable() {
			save(1L, Gender.FEMALE, "XXXX", 99, ContactFrequency.RARE, null, false, List.of());

			assertThat(findBest(base().build())).isEmpty();
		}

		@Test
		@DisplayName("이미 매칭된 상대는 제외한다")
		void excludesAlreadyMatched() {
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of());
			save(2L, "XXXX", 99, ContactFrequency.RARE, List.of());

			assertThat(findBest(base().excludeMemberIds(List.of(1L)).build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("같은 전공을 제외한다")
		void excludesSameMajor() {
			save(1L, Gender.FEMALE, "XXXX", 99, ContactFrequency.RARE, "컴퓨터공학", true, List.of());
			save(2L, Gender.FEMALE, "XXXX", 99, ContactFrequency.RARE, "경영학", true, List.of());

			assertThat(findBest(base().excludeMajor("컴퓨터공학").build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("나이 범위를 벗어난 후보는 제외한다")
		void filtersByAgeRange() {
			save(1L, "XXXX", 21, ContactFrequency.RARE, List.of());
			save(2L, "XXXX", 25, ContactFrequency.RARE, List.of());
			save(3L, "XXXX", 30, ContactFrequency.RARE, List.of());

			assertThat(findBest(base().minAge(23).maxAge(27).build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("MBTI 필수 조건은 요청 글자를 '전부' 가진 후보만 통과시킨다")
		void requiresAllMbtiTraits() {
			save(1L, "ENFP", 99, ContactFrequency.RARE, List.of());
			save(2L, "INFP", 99, ContactFrequency.RARE, List.of());

			assertThat(findBest(base().requiredMbtiTraits("EN").build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(1L);
		}

		@Test
		@DisplayName("MBTI 필수 조건의 와일드카드 문자는 글자 그대로 취급된다")
		void mbtiTraitIsNotAWildcard() {
			// LIKE '%x%' 였다면 사용자가 '%' 를 보낼 때 모든 후보가 통과한다.
			// LOCATE 는 와일드카드 개념이 없어서 '%' 를 글자 그대로 찾는다.
			save(1L, "ENFP", 99, ContactFrequency.RARE, List.of());

			assertThat(findBest(base().requiredMbtiTraits("%").build())).isEmpty();
		}

		@Test
		@DisplayName("연락빈도 필수 조건이 걸리면 일치하는 후보만 남는다")
		void requiresContactFrequency() {
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of());
			save(2L, "XXXX", 99, ContactFrequency.FREQUENT, List.of());

			assertThat(findBest(base().requiredContactFrequency(ContactFrequency.FREQUENT).build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("취미 필수 조건이 걸리면 해당 취미 보유자만 남는다")
		void requiresHobbyCategory() {
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of(HobbyCategory.MUSIC));
			save(2L, "XXXX", 99, ContactFrequency.RARE, List.of(HobbyCategory.GAME));

			assertThat(findBest(base()
				.requiredHobbyCategory(HobbyCategory.GAME)
				.scoreHobbyCategory(HobbyCategory.GAME)
				.build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("조건을 만족하는 후보가 없으면 빈 결과를 준다")
		void emptyWhenNothingMatches() {
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of());

			assertThat(findBest(base().minAge(200).build())).isEmpty();
		}
	}

	// ================= 표본 추출 =================

	@Nested
	@DisplayName("표본 추출")
	class Sampling {

		@Test
		@DisplayName("표본 크기를 넘는 후보는 보지 않는다")
		void looksAtSampleSizeOnly() {
			// random_key 를 member_id 와 같게 고정해 표본에 들어갈 순서를 결정적으로 만든다.
			// 키가 가장 큰 후보에게 만점을 줘도 표본이 앞 2명이면 뽑히지 않아야 한다.
			for (long id = 1; id <= 5; id++) {
				save(id, "XXXX", 99, ContactFrequency.RARE, List.of());
			}
			save(6L, "ENFP", MY_AGE, ContactFrequency.NORMAL, List.of());   // 만점 후보
			fixRandomKeysByMemberId();

			Optional<MatchingCandidate> best = findBest(fullScore().sampleSize(2).randomStart(0).build());

			assertThat(best).isPresent();
			assertThat(best.get().getMemberId()).isNotEqualTo(6L);
		}

		@Test
		@DisplayName("표본이 전체를 덮으면 만점 후보가 뽑힌다")
		void findsBestWhenSampleCoversAll() {
			for (long id = 1; id <= 5; id++) {
				save(id, "XXXX", 99, ContactFrequency.RARE, List.of());
			}
			save(6L, "ENFP", MY_AGE, ContactFrequency.NORMAL, List.of());
			fixRandomKeysByMemberId();

			assertThat(findBest(fullScore().build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(6L);
		}

		@Test
		@DisplayName("창에 후보가 있으면 randomStart 보다 앞선 키는 표본에서 빠진다")
		void randomStartSkipsEarlierKeys() {
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of());
			save(2L, "XXXX", 99, ContactFrequency.RARE, List.of());
			save(3L, "XXXX", 99, ContactFrequency.RARE, List.of());
			fixRandomKeysByMemberId();   // random_key = member_id

			assertThat(findBest(base().randomStart(3).build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(3L);
		}

		@Test
		@DisplayName("창이 비면 처음부터 다시 훑어 후보를 찾아낸다")
		void wrapsAroundWhenWindowIsEmpty() {
			// random_key 는 삽입할 때 0~10억 중 하나로 정해지고, randomStart 는 요청마다
			// 0~9억 중 하나로 새로 뽑힌다. 후보 전원의 키가 randomStart 보다 작으면
			// 창이 비는데, 예전에는 그대로 '후보 없음'이 되어 요청이 실패했다.
			// 후보 수가 적을수록 자주 걸린다 - 조건 통과자가 1명이면 45%, 3명이면 18%다.
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of());
			save(2L, "XXXX", 99, ContactFrequency.RARE, List.of());
			fixRandomKeysByMemberId();   // random_key = 1, 2

			assertThat(findBest(base().randomStart(10).build())).isPresent();
		}

		@Test
		@DisplayName("다시 훑을 때도 필수 조건은 그대로 지킨다")
		void wrapAroundStillRespectsFilters() {
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of());
			save(2L, "XXXX", 99, ContactFrequency.FREQUENT, List.of());
			fixRandomKeysByMemberId();

			assertThat(findBest(base()
				.randomStart(10)
				.requiredContactFrequency(ContactFrequency.FREQUENT)
				.build()))
				.get()
				.extracting(MatchingCandidate::getMemberId)
				.isEqualTo(2L);
		}

		@Test
		@DisplayName("다시 훑어도 조건에 맞는 후보가 없으면 빈 결과를 준다")
		void wrapAroundStillEmptyWhenNothingMatches() {
			// 재조회가 조건을 무시하고 아무나 집어오지 않는지 본다.
			save(1L, "XXXX", 99, ContactFrequency.RARE, List.of());
			fixRandomKeysByMemberId();

			assertThat(findBest(base().randomStart(10).minAge(200).build())).isEmpty();
		}

		@Test
		@DisplayName("동점자가 여럿이면 매번 같은 사람만 나오지 않는다")
		void tiedCandidatesAreShuffled() {
			// ORDER BY 점수 DESC, RAND() 가 동점 그룹 안에서 무작위로 섞는지 본다.
			// 전원 동점이므로 20회 중 최소 두 종류는 나와야 한다.
			// 한 명만 계속 나올 확률은 후보 10명 기준 (1/10)^19 로 사실상 0이다.
			for (long id = 1; id <= 10; id++) {
				save(id, "XXXX", 99, ContactFrequency.RARE, List.of());
			}

			Set<Long> seen = new HashSet<>();
			for (int i = 0; i < 20; i++) {
				findBest(base().build()).ifPresent(c -> seen.add(c.getMemberId()));
			}

			assertThat(seen).hasSizeGreaterThan(1);
		}
	}

	// ================= 헬퍼 =================

	private Optional<MatchingCandidate> findBest(MatchingCandidateSearchCondition condition) {
		em.flush();
		em.clear();
		return repository.findBestCandidate(condition);
	}

	/** random_key 를 member_id 와 같게 만들어 표본 순서를 결정적으로 고정한다. */
	private void fixRandomKeysByMemberId() {
		em.flush();
		em.createNativeQuery("UPDATE matching_candidate SET random_key = member_id").executeUpdate();
		em.clear();
	}

	private void save(long memberId, String mbti, int age, ContactFrequency contact, List<HobbyCategory> hobbies) {
		save(memberId, Gender.FEMALE, mbti, age, contact, null, true, hobbies);
	}

	private void save(long memberId, Gender gender, String mbti, int age, ContactFrequency contact,
		String major, boolean matchable, List<HobbyCategory> hobbies) {
		// KoreanAge 는 '만 나이 + 1' 이라 생일을 age-1 년 전으로 잡아야 의도한 나이가 된다.
		em.persist(MatchingCandidate.create(memberId, memberId, gender, mbti, major, contact,
			new ArrayList<>(hobbies), LocalDate.now().minusYears(age - 1L), matchable));
	}

	/** 점수도 조건도 걸지 않은 기본형. FEMALE 전체가 대상이 된다. */
	private ConditionBuilder base() {
		return new ConditionBuilder();
	}

	/** MBTI·나이·연락빈도 점수를 모두 켠 형태. */
	private ConditionBuilder fullScore() {
		return new ConditionBuilder()
			.myAge(MY_AGE)
			.scoreMbtiTraits("ENFP")
			.scoreAgeOption(AgeOption.EQUAL)
			.scoreContactFrequency(ContactFrequency.NORMAL);
	}

	private static final class ConditionBuilder {
		private int randomStart = 0;
		private int sampleSize = FULL_SAMPLE;
		private Gender targetGender = Gender.FEMALE;
		private String excludeMajor;
		private List<Long> excludeMemberIds = List.of();
		private Integer minAge;
		private Integer maxAge;
		private String requiredMbtiTraits;
		private ContactFrequency requiredContactFrequency;
		private HobbyCategory requiredHobbyCategory;
		private Integer myAge;
		private String scoreMbtiTraits;
		private HobbyCategory scoreHobbyCategory;
		private AgeOption scoreAgeOption;
		private ContactFrequency scoreContactFrequency;

		ConditionBuilder randomStart(int v) { this.randomStart = v; return this; }
		ConditionBuilder sampleSize(int v) { this.sampleSize = v; return this; }
		ConditionBuilder targetGender(Gender v) { this.targetGender = v; return this; }
		ConditionBuilder excludeMajor(String v) { this.excludeMajor = v; return this; }
		ConditionBuilder excludeMemberIds(List<Long> v) { this.excludeMemberIds = v; return this; }
		ConditionBuilder minAge(Integer v) { this.minAge = v; return this; }
		ConditionBuilder maxAge(Integer v) { this.maxAge = v; return this; }
		ConditionBuilder requiredMbtiTraits(String v) { this.requiredMbtiTraits = v; return this; }
		ConditionBuilder requiredContactFrequency(ContactFrequency v) { this.requiredContactFrequency = v; return this; }
		ConditionBuilder requiredHobbyCategory(HobbyCategory v) { this.requiredHobbyCategory = v; return this; }
		ConditionBuilder myAge(Integer v) { this.myAge = v; return this; }
		ConditionBuilder scoreMbtiTraits(String v) { this.scoreMbtiTraits = v; return this; }
		ConditionBuilder scoreHobbyCategory(HobbyCategory v) { this.scoreHobbyCategory = v; return this; }
		ConditionBuilder scoreAgeOption(AgeOption v) { this.scoreAgeOption = v; return this; }
		ConditionBuilder scoreContactFrequency(ContactFrequency v) { this.scoreContactFrequency = v; return this; }

		MatchingCandidateSearchCondition build() {
			return new MatchingCandidateSearchCondition(
				randomStart, sampleSize, targetGender, excludeMajor, excludeMemberIds,
				minAge, maxAge, requiredMbtiTraits, requiredContactFrequency, requiredHobbyCategory,
				myAge, scoreMbtiTraits, scoreHobbyCategory, scoreAgeOption, scoreContactFrequency);
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = MatchingCandidate.class)
	@EnableJpaRepositories(basePackageClasses = MatchingCandidateRepository.class)
	static class Config {
	}
}
