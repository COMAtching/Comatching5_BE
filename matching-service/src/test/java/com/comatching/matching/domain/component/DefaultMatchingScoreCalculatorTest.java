package com.comatching.matching.domain.component;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.enums.AgeOption;

class DefaultMatchingScoreCalculatorTest {

	private DefaultMatchingScoreCalculator calculator;

	@BeforeEach
	void setUp() {
		calculator = new DefaultMatchingScoreCalculator();
	}

	private MatchingCandidate createCandidate(String mbti, int age, ContactFrequency contactFrequency,
		List<HobbyCategory> hobbies) {
		return MatchingCandidate.create(
			1L, 1L, Gender.FEMALE, mbti, "컴퓨터공학과",
			contactFrequency, hobbies, LocalDate.now().minusYears(age - 1), true
		);
	}

	@Nested
	@DisplayName("MBTI 점수 계산")
	class MbtiScore {

		@Test
		@DisplayName("MBTI 옵션 2글자가 모두 일치하면 20점을 얻는다")
		void shouldGet20PointsWhenBothMbtiMatch() {
			// given
			MatchingCandidate candidate = createCandidate("ISTJ", 25, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, "IS", null, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(20);
		}

		@Test
		@DisplayName("MBTI 옵션 2글자 중 1개만 일치하면 10점을 얻는다")
		void shouldGet10PointsWhenOneMbtiMatch() {
			// given
			MatchingCandidate candidate = createCandidate("ISTJ", 25, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, "IE", null, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(10);
		}
	}

	@Nested
	@DisplayName("나이 점수 계산")
	class AgeScore {

		@Test
		@DisplayName("동갑 조건을 만족하면 20점을 얻는다")
		void shouldGet20PointsWhenAgeEqual() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 25, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(AgeOption.EQUAL, null, null, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(20);
		}

		@Test
		@DisplayName("연상 조건을 만족하면 20점을 얻는다")
		void shouldGet20PointsWhenOlder() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 30, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(AgeOption.OLDER, null, null, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(20);
		}

		@Test
		@DisplayName("연하 조건을 만족하면 20점을 얻는다")
		void shouldGet20PointsWhenYounger() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 22, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(AgeOption.YOUNGER, null, null, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(20);
		}

		@Test
		@DisplayName("나이 조건을 만족하지 않으면 0점이다")
		void shouldGet0PointsWhenAgeNotMatch() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 30, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(AgeOption.YOUNGER, null, null, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(0);
		}
	}

	@Nested
	@DisplayName("취미 점수 계산")
	class HobbyScore {

		@Test
		@DisplayName("취미가 3개 이상 일치하면 20점을 얻는다")
		void shouldGet20PointsWhenThreeOrMoreHobbiesMatch() {
			// given
			List<HobbyCategory> hobbies = List.of(
				HobbyCategory.SPORTS, HobbyCategory.SPORTS, HobbyCategory.SPORTS
			);
			MatchingCandidate candidate = createCandidate("ENFP", 25, null, hobbies);
			MatchingRequest request = new MatchingRequest(null, null, HobbyCategory.SPORTS, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(20);
		}

		@Test
		@DisplayName("취미가 2개 일치하면 15점을 얻는다")
		void shouldGet15PointsWhenTwoHobbiesMatch() {
			// given
			List<HobbyCategory> hobbies = List.of(HobbyCategory.SPORTS, HobbyCategory.SPORTS);
			MatchingCandidate candidate = createCandidate("ENFP", 25, null, hobbies);
			MatchingRequest request = new MatchingRequest(null, null, HobbyCategory.SPORTS, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(15);
		}

		@Test
		@DisplayName("취미가 1개 일치하면 10점을 얻는다")
		void shouldGet10PointsWhenOneHobbyMatches() {
			// given
			List<HobbyCategory> hobbies = List.of(HobbyCategory.SPORTS);
			MatchingCandidate candidate = createCandidate("ENFP", 25, null, hobbies);
			MatchingRequest request = new MatchingRequest(null, null, HobbyCategory.SPORTS, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(10);
		}

		@Test
		@DisplayName("취미가 일치하지 않으면 0점이다")
		void shouldGet0PointsWhenNoHobbyMatches() {
			// given
			List<HobbyCategory> hobbies = List.of(HobbyCategory.CULTURE);
			MatchingCandidate candidate = createCandidate("ENFP", 25, null, hobbies);
			MatchingRequest request = new MatchingRequest(null, null, HobbyCategory.SPORTS, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(0);
		}
	}

	@Nested
	@DisplayName("연락 빈도 점수 계산")
	class ContactScore {

		@Test
		@DisplayName("연락 빈도가 일치하면 10점을 얻는다")
		void shouldGet10PointsWhenContactFrequencyMatches() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 25, ContactFrequency.FREQUENT, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, null, null, ContactFrequency.FREQUENT, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(10);
		}

		@Test
		@DisplayName("연락 빈도가 일치하지 않으면 0점이다")
		void shouldGet0PointsWhenContactFrequencyNotMatches() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 25, ContactFrequency.FREQUENT, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, null, null, ContactFrequency.RARE, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(0);
		}
	}

	@Nested
	@DisplayName("복합 점수 계산")
	class CombinedScore {

		@Test
		@DisplayName("모든 조건이 일치하면 최대 점수를 얻는다")
		void shouldGetMaxScoreWhenAllConditionsMatch() {
			// given
			List<HobbyCategory> hobbies = List.of(
				HobbyCategory.SPORTS, HobbyCategory.SPORTS, HobbyCategory.SPORTS
			);
			MatchingCandidate candidate = createCandidate("ISTJ", 25, ContactFrequency.FREQUENT, hobbies);
			MatchingRequest request = new MatchingRequest(
				AgeOption.EQUAL, "IS", HobbyCategory.SPORTS, ContactFrequency.FREQUENT, false, null
			);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			int score = calculator.calculate(candidate, request, myAge);

			// then
			assertThat(score).isEqualTo(70); // MBTI 20 + 나이 20 + 취미 20 + 연락빈도 10
		}
	}
}
