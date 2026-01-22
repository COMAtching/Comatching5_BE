package com.comatching.matching.domain.component;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.enums.AgeOption;
import com.comatching.matching.domain.enums.ImportantOption;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;

@ExtendWith(MockitoExtension.class)
class MatchingProcessorTest {

	@InjectMocks
	private MatchingProcessor matchingProcessor;

	@Mock
	private MatchingCandidateRepository candidateRepository;

	@Mock
	private MatchingHistoryRepository historyRepository;

	@Mock
	private MatchingScoreCalculator scoreCalculator;

	@Mock
	private ImportantConditionCheckerFactory conditionCheckerFactory;

	private MatchingCandidate createCandidate(Long memberId, String mbti, int age) {
		return MatchingCandidate.create(
			memberId, 1L, Gender.FEMALE, mbti, "디자인학과",
			ContactFrequency.FREQUENT, List.of(HobbyCategory.SPORTS),
			LocalDate.now().minusYears(age - 1), true
		);
	}

	private ProfileResponse createProfile(Long memberId, Gender gender, int age) {
		return ProfileResponse.builder()
			.memberId(memberId)
			.gender(gender)
			.mbti("ISTJ")
			.major("컴퓨터공학과")
			.birthDate(LocalDate.now().minusYears(age - 1))
			.build();
	}

	@Nested
	@DisplayName("process 메서드")
	class Process {

		@Test
		@DisplayName("가장 높은 점수의 후보자를 반환한다")
		void shouldReturnHighestScoreCandidate() {
			// given
			Long memberId = 1L;
			ProfileResponse myProfile = createProfile(memberId, Gender.MALE, 25);
			MatchingRequest request = new MatchingRequest(null, "IS", null, null, false, null);

			MatchingCandidate candidate1 = createCandidate(2L, "ISTJ", 25);
			MatchingCandidate candidate2 = createCandidate(3L, "ENFP", 25);
			List<MatchingCandidate> candidates = List.of(candidate1, candidate2);

			given(historyRepository.findPartnerIdsByMemberId(memberId)).willReturn(new ArrayList<>());
			given(candidateRepository.findPotentialCandidates(eq(Gender.FEMALE), isNull(), anyList()))
				.willReturn(candidates);
			given(conditionCheckerFactory.check(isNull(), any(), any(), any())).willReturn(true);
			given(scoreCalculator.calculate(eq(candidate1), eq(request), any(KoreanAge.class))).willReturn(40);
			given(scoreCalculator.calculate(eq(candidate2), eq(request), any(KoreanAge.class))).willReturn(10);

			// when
			MatchingCandidate result = matchingProcessor.process(memberId, myProfile, request);

			// then
			assertThat(result.getMemberId()).isEqualTo(2L);
		}

		@Test
		@DisplayName("중요 조건을 만족하지 않는 후보자는 필터링된다")
		void shouldFilterCandidatesNotMeetingImportantCondition() {
			// given
			Long memberId = 1L;
			ProfileResponse myProfile = createProfile(memberId, Gender.MALE, 25);
			MatchingRequest request = new MatchingRequest(AgeOption.EQUAL, null, null, null, false, ImportantOption.AGE);

			MatchingCandidate candidate1 = createCandidate(2L, "ISTJ", 30); // 조건 미충족
			MatchingCandidate candidate2 = createCandidate(3L, "ENFP", 25); // 조건 충족
			List<MatchingCandidate> candidates = List.of(candidate1, candidate2);

			given(historyRepository.findPartnerIdsByMemberId(memberId)).willReturn(new ArrayList<>());
			given(candidateRepository.findPotentialCandidates(eq(Gender.FEMALE), isNull(), anyList()))
				.willReturn(candidates);
			given(conditionCheckerFactory.check(eq(ImportantOption.AGE), eq(candidate1), eq(request), any()))
				.willReturn(false);
			given(conditionCheckerFactory.check(eq(ImportantOption.AGE), eq(candidate2), eq(request), any()))
				.willReturn(true);
			given(scoreCalculator.calculate(eq(candidate2), eq(request), any(KoreanAge.class))).willReturn(20);

			// when
			MatchingCandidate result = matchingProcessor.process(memberId, myProfile, request);

			// then
			assertThat(result.getMemberId()).isEqualTo(3L);
		}

		@Test
		@DisplayName("동점인 경우 후보자 중 하나를 무작위로 반환한다")
		void shouldReturnRandomCandidateWhenTied() {
			// given
			Long memberId = 1L;
			ProfileResponse myProfile = createProfile(memberId, Gender.MALE, 25);
			MatchingRequest request = new MatchingRequest(null, null, null, null, false, null);

			MatchingCandidate candidate1 = createCandidate(2L, "ISTJ", 25);
			MatchingCandidate candidate2 = createCandidate(3L, "ENFP", 25);
			List<MatchingCandidate> candidates = List.of(candidate1, candidate2);

			given(historyRepository.findPartnerIdsByMemberId(memberId)).willReturn(new ArrayList<>());
			given(candidateRepository.findPotentialCandidates(eq(Gender.FEMALE), isNull(), anyList()))
				.willReturn(candidates);
			given(conditionCheckerFactory.check(isNull(), any(), any(), any())).willReturn(true);
			given(scoreCalculator.calculate(any(), eq(request), any(KoreanAge.class))).willReturn(20);

			// when
			MatchingCandidate result = matchingProcessor.process(memberId, myProfile, request);

			// then
			assertThat(result.getMemberId()).isIn(2L, 3L);
		}

		@Test
		@DisplayName("후보자가 없으면 BusinessException을 던진다")
		void shouldThrowExceptionWhenNoCandidates() {
			// given
			Long memberId = 1L;
			ProfileResponse myProfile = createProfile(memberId, Gender.MALE, 25);
			MatchingRequest request = new MatchingRequest(null, null, null, null, false, null);

			given(historyRepository.findPartnerIdsByMemberId(memberId)).willReturn(new ArrayList<>());
			given(candidateRepository.findPotentialCandidates(eq(Gender.FEMALE), isNull(), anyList()))
				.willReturn(new ArrayList<>());

			// when & then
			assertThatThrownBy(() -> matchingProcessor.process(memberId, myProfile, request))
				.isInstanceOf(BusinessException.class);
		}

		@Test
		@DisplayName("모든 후보자가 중요 조건을 만족하지 못하면 BusinessException을 던진다")
		void shouldThrowExceptionWhenAllCandidatesFiltered() {
			// given
			Long memberId = 1L;
			ProfileResponse myProfile = createProfile(memberId, Gender.MALE, 25);
			MatchingRequest request = new MatchingRequest(AgeOption.EQUAL, null, null, null, false, ImportantOption.AGE);

			MatchingCandidate candidate1 = createCandidate(2L, "ISTJ", 30);
			List<MatchingCandidate> candidates = List.of(candidate1);

			given(historyRepository.findPartnerIdsByMemberId(memberId)).willReturn(new ArrayList<>());
			given(candidateRepository.findPotentialCandidates(eq(Gender.FEMALE), isNull(), anyList()))
				.willReturn(candidates);
			given(conditionCheckerFactory.check(eq(ImportantOption.AGE), any(), eq(request), any()))
				.willReturn(false);

			// when & then
			assertThatThrownBy(() -> matchingProcessor.process(memberId, myProfile, request))
				.isInstanceOf(BusinessException.class);
		}

		@Test
		@DisplayName("sameMajorOption이 true면 같은 전공을 제외한다")
		void shouldExcludeSameMajorWhenOptionIsTrue() {
			// given
			Long memberId = 1L;
			ProfileResponse myProfile = createProfile(memberId, Gender.MALE, 25);
			MatchingRequest request = new MatchingRequest(null, null, null, null, true, null);

			MatchingCandidate candidate = createCandidate(2L, "ISTJ", 25);
			List<MatchingCandidate> candidates = List.of(candidate);

			given(historyRepository.findPartnerIdsByMemberId(memberId)).willReturn(new ArrayList<>());
			given(candidateRepository.findPotentialCandidates(eq(Gender.FEMALE), eq("컴퓨터공학과"), anyList()))
				.willReturn(candidates);
			given(conditionCheckerFactory.check(isNull(), any(), any(), any())).willReturn(true);
			given(scoreCalculator.calculate(any(), eq(request), any(KoreanAge.class))).willReturn(20);

			// when
			MatchingCandidate result = matchingProcessor.process(memberId, myProfile, request);

			// then
			verify(candidateRepository).findPotentialCandidates(eq(Gender.FEMALE), eq("컴퓨터공학과"), anyList());
			assertThat(result).isNotNull();
		}
	}
}
