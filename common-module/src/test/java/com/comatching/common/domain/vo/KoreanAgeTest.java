package com.comatching.common.domain.vo;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class KoreanAgeTest {

	@Nested
	@DisplayName("fromBirthDate 테스트")
	class FromBirthDate {

		@Test
		@DisplayName("생년월일로 한국식 나이를 계산한다")
		void shouldCalculateKoreanAge() {
			// given
			int currentYear = LocalDate.now().getYear();
			LocalDate birthDate = LocalDate.of(2000, 1, 1);

			// when
			KoreanAge age = KoreanAge.fromBirthDate(birthDate);

			// then
			assertThat(age.getValue()).isEqualTo(currentYear - 2000 + 1);
		}

		@Test
		@DisplayName("생년월일이 null이면 null을 반환한다")
		void shouldReturnNullWhenBirthDateIsNull() {
			// given & when
			KoreanAge age = KoreanAge.fromBirthDate(null);

			// then
			assertThat(age).isNull();
		}
	}

	@Nested
	@DisplayName("of 테스트")
	class Of {

		@Test
		@DisplayName("정수로 KoreanAge를 생성한다")
		void shouldCreateFromInteger() {
			// given
			int ageValue = 25;

			// when
			KoreanAge age = KoreanAge.of(ageValue);

			// then
			assertThat(age.getValue()).isEqualTo(25);
		}
	}

	@Nested
	@DisplayName("isEqual 테스트")
	class IsEqual {

		@Test
		@DisplayName("나이가 같으면 true를 반환한다")
		void shouldReturnTrueWhenEqual() {
			// given
			KoreanAge age1 = KoreanAge.of(25);
			KoreanAge age2 = KoreanAge.of(25);

			// when
			boolean result = age1.isEqual(age2);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("나이가 다르면 false를 반환한다")
		void shouldReturnFalseWhenNotEqual() {
			// given
			KoreanAge age1 = KoreanAge.of(25);
			KoreanAge age2 = KoreanAge.of(26);

			// when
			boolean result = age1.isEqual(age2);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("비교 대상이 null이면 false를 반환한다")
		void shouldReturnFalseWhenOtherIsNull() {
			// given
			KoreanAge age = KoreanAge.of(25);

			// when
			boolean result = age.isEqual(null);

			// then
			assertThat(result).isFalse();
		}
	}

	@Nested
	@DisplayName("isOlderThan 테스트")
	class IsOlderThan {

		@Test
		@DisplayName("나이가 많으면 true를 반환한다")
		void shouldReturnTrueWhenOlder() {
			// given
			KoreanAge candidate = KoreanAge.of(30);
			KoreanAge my = KoreanAge.of(25);

			// when
			boolean result = candidate.isOlderThan(my);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("나이가 적으면 false를 반환한다")
		void shouldReturnFalseWhenYounger() {
			// given
			KoreanAge candidate = KoreanAge.of(20);
			KoreanAge my = KoreanAge.of(25);

			// when
			boolean result = candidate.isOlderThan(my);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("나이가 같으면 false를 반환한다")
		void shouldReturnFalseWhenEqual() {
			// given
			KoreanAge candidate = KoreanAge.of(25);
			KoreanAge my = KoreanAge.of(25);

			// when
			boolean result = candidate.isOlderThan(my);

			// then
			assertThat(result).isFalse();
		}
	}

	@Nested
	@DisplayName("isYoungerThan 테스트")
	class IsYoungerThan {

		@Test
		@DisplayName("나이가 적으면 true를 반환한다")
		void shouldReturnTrueWhenYounger() {
			// given
			KoreanAge candidate = KoreanAge.of(20);
			KoreanAge my = KoreanAge.of(25);

			// when
			boolean result = candidate.isYoungerThan(my);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("나이가 많으면 false를 반환한다")
		void shouldReturnFalseWhenOlder() {
			// given
			KoreanAge candidate = KoreanAge.of(30);
			KoreanAge my = KoreanAge.of(25);

			// when
			boolean result = candidate.isYoungerThan(my);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("나이가 같으면 false를 반환한다")
		void shouldReturnFalseWhenEqual() {
			// given
			KoreanAge candidate = KoreanAge.of(25);
			KoreanAge my = KoreanAge.of(25);

			// when
			boolean result = candidate.isYoungerThan(my);

			// then
			assertThat(result).isFalse();
		}
	}
}
