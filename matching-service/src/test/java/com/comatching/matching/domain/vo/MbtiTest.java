package com.comatching.matching.domain.vo;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MbtiTest {

	@Nested
	@DisplayName("생성자 테스트")
	class Constructor {

		@Test
		@DisplayName("소문자 입력시 대문자로 변환된다")
		void shouldConvertToUpperCase() {
			// given
			String lowerCaseMbti = "istj";

			// when
			Mbti mbti = new Mbti(lowerCaseMbti);

			// then
			assertThat(mbti.getValue()).isEqualTo("ISTJ");
		}

		@Test
		@DisplayName("null 입력시 value는 null이다")
		void shouldHandleNull() {
			// given & when
			Mbti mbti = new Mbti(null);

			// then
			assertThat(mbti.getValue()).isNull();
		}

		@Test
		@DisplayName("빈 문자열 입력시 value는 null이다")
		void shouldHandleBlankString() {
			// given & when
			Mbti mbti = new Mbti("   ");

			// then
			assertThat(mbti.getValue()).isNull();
		}
	}

	@Nested
	@DisplayName("containsAll 테스트")
	class ContainsAll {

		@Test
		@DisplayName("요청 MBTI가 모두 포함되면 true를 반환한다")
		void shouldReturnTrueWhenAllCharsContained() {
			// given
			Mbti candidate = new Mbti("ISTJ");
			Mbti request = new Mbti("IS");

			// when
			boolean result = candidate.containsAll(request);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("요청 MBTI 중 일부가 없으면 false를 반환한다")
		void shouldReturnFalseWhenSomeCharsMissing() {
			// given
			Mbti candidate = new Mbti("ISTJ");
			Mbti request = new Mbti("EN");

			// when
			boolean result = candidate.containsAll(request);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("요청 MBTI가 null이면 true를 반환한다")
		void shouldReturnTrueWhenRequestIsNull() {
			// given
			Mbti candidate = new Mbti("ISTJ");

			// when
			boolean result = candidate.containsAll(null);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("요청 MBTI의 value가 null이면 true를 반환한다")
		void shouldReturnTrueWhenRequestValueIsNull() {
			// given
			Mbti candidate = new Mbti("ISTJ");
			Mbti request = new Mbti(null);

			// when
			boolean result = candidate.containsAll(request);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("후보자 MBTI가 null이면 true를 반환한다")
		void shouldReturnTrueWhenCandidateValueIsNull() {
			// given
			Mbti candidate = new Mbti(null);
			Mbti request = new Mbti("IS");

			// when
			boolean result = candidate.containsAll(request);

			// then
			assertThat(result).isTrue();
		}
	}

	@Nested
	@DisplayName("calculateScore 테스트")
	class CalculateScore {

		@Test
		@DisplayName("옵션 2글자가 모두 일치하면 20점을 반환한다")
		void shouldReturn20WhenBothMatch() {
			// given
			Mbti candidate = new Mbti("ISTJ");
			Mbti request = new Mbti("IS"); // 매칭 옵션은 2글자

			// when
			int score = candidate.calculateScore(request);

			// then
			assertThat(score).isEqualTo(20);
		}

		@Test
		@DisplayName("옵션 2글자 중 1개만 일치하면 10점을 반환한다")
		void shouldReturn10WhenOneMatch() {
			// given
			Mbti candidate = new Mbti("ISTJ");
			Mbti request = new Mbti("IE"); // I만 일치

			// when
			int score = candidate.calculateScore(request);

			// then
			assertThat(score).isEqualTo(10);
		}

		@Test
		@DisplayName("일치하는 것이 없으면 0점을 반환한다")
		void shouldReturn0WhenNoneMatch() {
			// given
			Mbti candidate = new Mbti("ISTJ");
			Mbti request = new Mbti("EN"); // 일치하는 것 없음

			// when
			int score = candidate.calculateScore(request);

			// then
			assertThat(score).isEqualTo(0);
		}

		@Test
		@DisplayName("요청 MBTI가 null이면 0점을 반환한다")
		void shouldReturn0WhenRequestIsNull() {
			// given
			Mbti candidate = new Mbti("ISTJ");

			// when
			int score = candidate.calculateScore(null);

			// then
			assertThat(score).isEqualTo(0);
		}

		@Test
		@DisplayName("후보자 MBTI가 null이면 0점을 반환한다")
		void shouldReturn0WhenCandidateIsNull() {
			// given
			Mbti candidate = new Mbti(null);
			Mbti request = new Mbti("ISTJ");

			// when
			int score = candidate.calculateScore(request);

			// then
			assertThat(score).isEqualTo(0);
		}
	}
}
