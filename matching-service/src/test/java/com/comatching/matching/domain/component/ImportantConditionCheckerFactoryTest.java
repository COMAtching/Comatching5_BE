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
import com.comatching.matching.domain.enums.ImportantOption;

class ImportantConditionCheckerFactoryTest {

	private ImportantConditionCheckerFactory factory;

	@BeforeEach
	void setUp() {
		factory = new ImportantConditionCheckerFactory();
	}

	private MatchingCandidate createCandidate(String mbti, int age, ContactFrequency contactFrequency,
		List<HobbyCategory> hobbies) {
		return MatchingCandidate.create(
			1L, 1L, Gender.FEMALE, mbti, "컴퓨터공학과",
			contactFrequency, hobbies, LocalDate.now().minusYears(age - 1), true
		);
	}

	@Nested
	@DisplayName("ImportantOption이 null인 경우")
	class NullOption {

		@Test
		@DisplayName("중요 조건이 null이면 true를 반환한다")
		void shouldReturnTrueWhenOptionIsNull() {
			// given
			MatchingCandidate candidate = createCandidate("ISTJ", 25, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, null, null, null, false, null);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(null, candidate, request, myAge);

			// then
			assertThat(result).isTrue();
		}
	}

	@Nested
	@DisplayName("AGE 중요 조건 검증")
	class AgeCondition {

		@Test
		@DisplayName("동갑 조건을 만족하면 true를 반환한다")
		void shouldReturnTrueWhenAgeEqual() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 25, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(AgeOption.EQUAL, null, null, null, false, ImportantOption.AGE);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.AGE, candidate, request, myAge);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("연상 조건을 만족하면 true를 반환한다")
		void shouldReturnTrueWhenOlder() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 30, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(AgeOption.OLDER, null, null, null, false, ImportantOption.AGE);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.AGE, candidate, request, myAge);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("나이 조건을 만족하지 않으면 false를 반환한다")
		void shouldReturnFalseWhenAgeNotMatch() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 30, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(AgeOption.YOUNGER, null, null, null, false, ImportantOption.AGE);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.AGE, candidate, request, myAge);

			// then
			assertThat(result).isFalse();
		}
	}

	@Nested
	@DisplayName("MBTI 중요 조건 검증")
	class MbtiCondition {

		@Test
		@DisplayName("요청 MBTI가 모두 포함되면 true를 반환한다")
		void shouldReturnTrueWhenMbtiContainsAll() {
			// given
			MatchingCandidate candidate = createCandidate("ISTJ", 25, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, "IS", null, null, false, ImportantOption.MBTI);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.MBTI, candidate, request, myAge);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("요청 MBTI가 포함되지 않으면 false를 반환한다")
		void shouldReturnFalseWhenMbtiNotContained() {
			// given
			MatchingCandidate candidate = createCandidate("ISTJ", 25, null, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, "EN", null, null, false, ImportantOption.MBTI);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.MBTI, candidate, request, myAge);

			// then
			assertThat(result).isFalse();
		}
	}

	@Nested
	@DisplayName("HOBBY 중요 조건 검증")
	class HobbyCondition {

		@Test
		@DisplayName("요청 취미가 포함되면 true를 반환한다")
		void shouldReturnTrueWhenHobbyContained() {
			// given
			List<HobbyCategory> hobbies = List.of(HobbyCategory.SPORTS, HobbyCategory.CULTURE);
			MatchingCandidate candidate = createCandidate("ENFP", 25, null, hobbies);
			MatchingRequest request = new MatchingRequest(null, null, HobbyCategory.SPORTS, null, false, ImportantOption.HOBBY);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.HOBBY, candidate, request, myAge);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("요청 취미가 포함되지 않으면 false를 반환한다")
		void shouldReturnFalseWhenHobbyNotContained() {
			// given
			List<HobbyCategory> hobbies = List.of(HobbyCategory.CULTURE);
			MatchingCandidate candidate = createCandidate("ENFP", 25, null, hobbies);
			MatchingRequest request = new MatchingRequest(null, null, HobbyCategory.SPORTS, null, false, ImportantOption.HOBBY);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.HOBBY, candidate, request, myAge);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("요청 취미가 null이면 true를 반환한다")
		void shouldReturnTrueWhenRequestHobbyIsNull() {
			// given
			List<HobbyCategory> hobbies = List.of(HobbyCategory.CULTURE);
			MatchingCandidate candidate = createCandidate("ENFP", 25, null, hobbies);
			MatchingRequest request = new MatchingRequest(null, null, null, null, false, ImportantOption.HOBBY);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.HOBBY, candidate, request, myAge);

			// then
			assertThat(result).isTrue();
		}
	}

	@Nested
	@DisplayName("CONTACT 중요 조건 검증")
	class ContactCondition {

		@Test
		@DisplayName("연락 빈도가 일치하면 true를 반환한다")
		void shouldReturnTrueWhenContactFrequencyMatches() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 25, ContactFrequency.FREQUENT, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, null, null, ContactFrequency.FREQUENT, false, ImportantOption.CONTACT);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.CONTACT, candidate, request, myAge);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("연락 빈도가 일치하지 않으면 false를 반환한다")
		void shouldReturnFalseWhenContactFrequencyNotMatches() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 25, ContactFrequency.FREQUENT, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, null, null, ContactFrequency.RARE, false, ImportantOption.CONTACT);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.CONTACT, candidate, request, myAge);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("요청 연락 빈도가 null이면 true를 반환한다")
		void shouldReturnTrueWhenRequestContactIsNull() {
			// given
			MatchingCandidate candidate = createCandidate("ENFP", 25, ContactFrequency.FREQUENT, new ArrayList<>());
			MatchingRequest request = new MatchingRequest(null, null, null, null, false, ImportantOption.CONTACT);
			KoreanAge myAge = KoreanAge.of(25);

			// when
			boolean result = factory.check(ImportantOption.CONTACT, candidate, request, myAge);

			// then
			assertThat(result).isTrue();
		}
	}
}
