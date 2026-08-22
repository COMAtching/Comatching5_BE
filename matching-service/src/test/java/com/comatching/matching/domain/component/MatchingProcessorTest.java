package com.comatching.matching.domain.component;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.enums.AgeOption;
import com.comatching.matching.domain.enums.ImportantOption;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateSearchCondition;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.global.exception.MatchingErrorCode;

/**
 * 점수 계산·필터링·표본 추출이 SQL 로 내려간 뒤 자바에 남은 것은
 * "요청을 조회 조건으로 번역하는 일"뿐이다. 여기서는 그 번역만 본다.
 *
 * 핵심은 importantOption 이 만드는 두 갈래다.
 *   - 필수 조건(WHERE)  : importantOption 이 지목한 하나만 걸린다
 *   - 점수 (ORDER BY)   : importantOption 과 무관하게 설정된 값이 전부 넘어간다
 *
 * SQL 자체의 동작은 MatchingCandidateRepositoryIT 가 실제 MySQL 로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchingProcessor 단위 테스트")
class MatchingProcessorTest {

	private static final Long MEMBER_ID = 1L;
	private static final int MY_AGE = 24;

	@InjectMocks
	private MatchingProcessor matchingProcessor;

	@Mock
	private MatchingCandidateRepository candidateRepository;

	@Mock
	private MatchingHistoryRepository historyRepository;

	@Nested
	@DisplayName("조회 결과 처리")
	class Result {

		@Test
		@DisplayName("리포지토리가 찾은 후보를 그대로 반환한다")
		void returnsCandidateFromRepository() {
			MatchingCandidate found = candidate(99L);
			givenBestCandidate(found);

			assertThat(matchingProcessor.process(MEMBER_ID, profile(Gender.MALE), request().build()))
				.isSameAs(found);
		}

		@Test
		@DisplayName("후보가 없으면 NO_MATCHING_CANDIDATE 를 던진다")
		void throwsWhenNoCandidate() {
			given(historyRepository.findMatchedMemberIdsByMemberId(MEMBER_ID)).willReturn(List.of());
			given(candidateRepository.findBestCandidate(any())).willReturn(Optional.empty());

			assertThatThrownBy(() -> matchingProcessor.process(MEMBER_ID, profile(Gender.MALE), request().build()))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", MatchingErrorCode.NO_MATCHING_CANDIDATE);
		}
	}

	@Nested
	@DisplayName("나이 중요 조건의 단락 처리")
	class AgeShortCircuit {

		// 예전 ImportantConditionCheckerFactory.checkAge 는 이 두 경우에 모든 후보를 탈락시켰다.
		// SQL 의 나이 조건은 그 경우 아예 걸리지 않아 반대로 전부 통과한다.
		// 기존 동작을 지키려고 쿼리 전에 끊는다. 조회 자체가 일어나면 안 된다.

		@Test
		@DisplayName("importantOption 이 AGE 인데 ageOption 이 없으면 조회 없이 예외를 던진다")
		void throwsWithoutQueryWhenAgeOptionMissing() {
			MatchingRequest request = request()
				.importantOption(ImportantOption.AGE)
				.ageOption(null)
				.build();

			assertThatThrownBy(() -> matchingProcessor.process(MEMBER_ID, profile(Gender.MALE), request))
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", MatchingErrorCode.NO_MATCHING_CANDIDATE);

			then(candidateRepository).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("importantOption 이 AGE 인데 내 생일이 없으면 조회 없이 예외를 던진다")
		void throwsWithoutQueryWhenMyAgeMissing() {
			ProfileResponse noBirthDate = ProfileResponse.builder()
				.memberId(MEMBER_ID)
				.gender(Gender.MALE)
				.major("컴퓨터공학과")
				.build();
			MatchingRequest request = request()
				.importantOption(ImportantOption.AGE)
				.ageOption(AgeOption.EQUAL)
				.build();

			assertThatThrownBy(() -> matchingProcessor.process(MEMBER_ID, noBirthDate, request))
				.isInstanceOf(BusinessException.class);

			then(candidateRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("필수 조건 — importantOption 이 지목한 하나만 걸린다")
	class RequiredConditions {

		@Test
		@DisplayName("MBTI 를 고르면 MBTI 만 필수가 되고 대문자로 정규화된다")
		void mbtiOnly() {
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(ImportantOption.MBTI)
				.mbtiOption("enfp")
				.hobbyOption(HobbyCategory.GAME)
				.contactFrequency(ContactFrequency.NORMAL)
				.build());

			assertThat(condition.requiredMbtiTraits()).isEqualTo("ENFP");
			assertThat(condition.requiredHobbyCategory()).isNull();
			assertThat(condition.requiredContactFrequency()).isNull();
		}

		@Test
		@DisplayName("취미를 고르면 취미만 필수가 된다")
		void hobbyOnly() {
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(ImportantOption.HOBBY)
				.mbtiOption("ENFP")
				.hobbyOption(HobbyCategory.GAME)
				.contactFrequency(ContactFrequency.NORMAL)
				.build());

			assertThat(condition.requiredHobbyCategory()).isEqualTo(HobbyCategory.GAME);
			assertThat(condition.requiredMbtiTraits()).isNull();
			assertThat(condition.requiredContactFrequency()).isNull();
		}

		@Test
		@DisplayName("연락빈도를 고르면 연락빈도만 필수가 된다")
		void contactOnly() {
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(ImportantOption.CONTACT)
				.mbtiOption("ENFP")
				.hobbyOption(HobbyCategory.GAME)
				.contactFrequency(ContactFrequency.NORMAL)
				.build());

			assertThat(condition.requiredContactFrequency()).isEqualTo(ContactFrequency.NORMAL);
			assertThat(condition.requiredMbtiTraits()).isNull();
			assertThat(condition.requiredHobbyCategory()).isNull();
		}

		@Test
		@DisplayName("아무것도 고르지 않으면 필수 조건이 하나도 걸리지 않는다")
		void noneWhenImportantOptionIsNull() {
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(null)
				.mbtiOption("ENFP")
				.hobbyOption(HobbyCategory.GAME)
				.contactFrequency(ContactFrequency.NORMAL)
				.build());

			assertThat(condition.requiredMbtiTraits()).isNull();
			assertThat(condition.requiredHobbyCategory()).isNull();
			assertThat(condition.requiredContactFrequency()).isNull();
		}

		@Test
		@DisplayName("MBTI 를 골라도 값이 비어 있으면 필수로 걸지 않는다")
		void blankMbtiIsNotRequired() {
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(ImportantOption.MBTI)
				.mbtiOption("  ")
				.build());

			assertThat(condition.requiredMbtiTraits()).isNull();
		}
	}

	@Nested
	@DisplayName("점수 — importantOption 과 무관하게 전부 전달된다")
	class ScoreParameters {

		@Test
		@DisplayName("필수로 걸리지 않은 옵션도 점수 파라미터로는 넘어간다")
		void allOptionsGoToScore() {
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(ImportantOption.MBTI)   // 필수는 MBTI 하나뿐
				.mbtiOption("ENFP")
				.hobbyOption(HobbyCategory.GAME)
				.contactFrequency(ContactFrequency.NORMAL)
				.ageOption(AgeOption.OLDER)
				.build());

			assertThat(condition.scoreMbtiTraits()).isEqualTo("ENFP");
			assertThat(condition.scoreHobbyCategory()).isEqualTo(HobbyCategory.GAME);
			assertThat(condition.scoreContactFrequency()).isEqualTo(ContactFrequency.NORMAL);
			assertThat(condition.scoreAgeOption()).isEqualTo(AgeOption.OLDER);
			assertThat(condition.myAge()).isEqualTo(MY_AGE);
		}
	}

	@Nested
	@DisplayName("나이 범위 계산")
	class AgeRange {

		@Test
		@DisplayName("나이 제한이 없으면 범위를 걸지 않는다")
		void noBoundsWithoutAgeLimit() {
			MatchingCandidateSearchCondition condition = capture(request().build());

			assertThat(condition.minAge()).isNull();
			assertThat(condition.maxAge()).isNull();
		}

		@Test
		@DisplayName("나이 제한 상한은 27세를 넘지 못한다")
		void clampsUpperBoundToMaxAllowedAge() {
			MatchingCandidateSearchCondition condition = capture(request()
				.minAgeOffset(20)
				.maxAgeOffset(40)
				.build());

			assertThat(condition.minAge()).isEqualTo(20);
			assertThat(condition.maxAge()).isEqualTo(27);
		}

		@Test
		@DisplayName("AGE 중요 조건 EQUAL 은 내 나이로 범위를 좁힌다")
		void equalNarrowsToMyAge() {
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(ImportantOption.AGE)
				.ageOption(AgeOption.EQUAL)
				.build());

			assertThat(condition.minAge()).isEqualTo(MY_AGE);
			assertThat(condition.maxAge()).isEqualTo(MY_AGE);
		}

		@Test
		@DisplayName("AGE 중요 조건 OLDER 는 하한을 내 나이 + 1 로 올린다")
		void olderRaisesLowerBound() {
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(ImportantOption.AGE)
				.ageOption(AgeOption.OLDER)
				.build());

			assertThat(condition.minAge()).isEqualTo(MY_AGE + 1);
			assertThat(condition.maxAge()).isNull();
		}

		@Test
		@DisplayName("AGE 중요 조건 YOUNGER 는 상한을 내 나이 - 1 로 내린다")
		void youngerLowersUpperBound() {
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(ImportantOption.AGE)
				.ageOption(AgeOption.YOUNGER)
				.build());

			assertThat(condition.maxAge()).isEqualTo(MY_AGE - 1);
			assertThat(condition.minAge()).isNull();
		}

		@Test
		@DisplayName("나이 제한과 AGE 중요 조건이 겹치면 더 엄격한 쪽을 쓴다")
		void takesStricterOfTwoSources() {
			// 제한은 20~27, OLDER 는 하한 25 를 요구한다 -> 하한은 25 가 이긴다
			MatchingCandidateSearchCondition condition = capture(request()
				.importantOption(ImportantOption.AGE)
				.ageOption(AgeOption.OLDER)
				.minAgeOffset(20)
				.maxAgeOffset(27)
				.build());

			assertThat(condition.minAge()).isEqualTo(MY_AGE + 1);
			assertThat(condition.maxAge()).isEqualTo(27);
		}
	}

	@Nested
	@DisplayName("그 밖의 조건")
	class Others {

		@Test
		@DisplayName("내 성별의 반대를 대상으로 삼는다")
		void targetsOppositeGender() {
			assertThat(capture(profile(Gender.MALE), request().build()).targetGender())
				.isEqualTo(Gender.FEMALE);
			assertThat(capture(profile(Gender.FEMALE), request().build()).targetGender())
				.isEqualTo(Gender.MALE);
		}

		@Test
		@DisplayName("sameMajorOption 이 켜지면 내 전공을 제외 대상으로 넘긴다")
		void passesMyMajorWhenExcludingSameMajor() {
			assertThat(capture(request().sameMajorOption(true).build()).excludeMajor())
				.isEqualTo("컴퓨터공학과");
			assertThat(capture(request().sameMajorOption(false).build()).excludeMajor())
				.isNull();
		}

		@Test
		@DisplayName("양방향 매칭 이력을 제외 목록으로 넘긴다")
		void passesMatchedHistoryAsExclusion() {
			given(historyRepository.findMatchedMemberIdsByMemberId(MEMBER_ID))
				.willReturn(List.of(7L, 8L));
			given(candidateRepository.findBestCandidate(any())).willReturn(Optional.of(candidate(99L)));

			matchingProcessor.process(MEMBER_ID, profile(Gender.MALE), request().build());

			assertThat(captureCondition().excludeMemberIds()).containsExactly(7L, 8L);
		}

		@Test
		@DisplayName("표본 크기와 시작점을 함께 넘긴다")
		void passesSamplingParameters() {
			MatchingCandidateSearchCondition condition = capture(request().build());

			assertThat(condition.sampleSize()).isEqualTo(5_000);
			assertThat(condition.randomStart())
				.isBetween(0, MatchingCandidate.RANDOM_KEY_START_BOUND - 1);
		}

		@Test
		@DisplayName("표본 시작점은 매 요청 새로 뽑는다")
		void randomStartVariesPerRequest() {
			given(historyRepository.findMatchedMemberIdsByMemberId(MEMBER_ID)).willReturn(List.of());
			given(candidateRepository.findBestCandidate(any())).willReturn(Optional.of(candidate(99L)));

			for (int i = 0; i < 30; i++) {
				matchingProcessor.process(MEMBER_ID, profile(Gender.MALE), request().build());
			}

			ArgumentCaptor<MatchingCandidateSearchCondition> captor =
				ArgumentCaptor.forClass(MatchingCandidateSearchCondition.class);
			then(candidateRepository).should(times(30)).findBestCandidate(captor.capture());

			assertThat(captor.getAllValues())
				.extracting(MatchingCandidateSearchCondition::randomStart)
				.doesNotHaveDuplicates();
		}
	}

	// ================= 헬퍼 =================

	private void givenBestCandidate(MatchingCandidate candidate) {
		given(historyRepository.findMatchedMemberIdsByMemberId(MEMBER_ID)).willReturn(List.of());
		given(candidateRepository.findBestCandidate(any())).willReturn(Optional.of(candidate));
	}

	private MatchingCandidateSearchCondition capture(MatchingRequest request) {
		return capture(profile(Gender.MALE), request);
	}

	private MatchingCandidateSearchCondition capture(ProfileResponse myProfile, MatchingRequest request) {
		given(historyRepository.findMatchedMemberIdsByMemberId(MEMBER_ID)).willReturn(List.of());
		given(candidateRepository.findBestCandidate(any())).willReturn(Optional.of(candidate(99L)));

		matchingProcessor.process(MEMBER_ID, myProfile, request);

		return captureCondition();
	}

	private MatchingCandidateSearchCondition captureCondition() {
		ArgumentCaptor<MatchingCandidateSearchCondition> captor =
			ArgumentCaptor.forClass(MatchingCandidateSearchCondition.class);
		then(candidateRepository).should(atLeastOnce()).findBestCandidate(captor.capture());
		return captor.getValue();
	}

	private MatchingCandidate candidate(Long memberId) {
		return MatchingCandidate.create(memberId, memberId, Gender.FEMALE, "ISTJ", "디자인학과",
			ContactFrequency.FREQUENT, List.of(HobbyCategory.SPORTS),
			LocalDate.now().minusYears(MY_AGE - 1L), true);
	}

	private ProfileResponse profile(Gender gender) {
		return ProfileResponse.builder()
			.memberId(MEMBER_ID)
			.gender(gender)
			.mbti("ISTJ")
			.major("컴퓨터공학과")
			.birthDate(LocalDate.now().minusYears(MY_AGE - 1L))
			.build();
	}

	private RequestBuilder request() {
		return new RequestBuilder();
	}

	/** MatchingRequest 는 필드가 많아 테스트마다 필요한 것만 지정할 수 있게 감싼다. */
	private static final class RequestBuilder {
		private AgeOption ageOption;
		private String mbtiOption;
		private HobbyCategory hobbyOption;
		private ContactFrequency contactFrequency;
		private boolean sameMajorOption;
		private ImportantOption importantOption;
		private Integer minAgeOffset;
		private Integer maxAgeOffset;

		RequestBuilder ageOption(AgeOption v) { this.ageOption = v; return this; }
		RequestBuilder mbtiOption(String v) { this.mbtiOption = v; return this; }
		RequestBuilder hobbyOption(HobbyCategory v) { this.hobbyOption = v; return this; }
		RequestBuilder contactFrequency(ContactFrequency v) { this.contactFrequency = v; return this; }
		RequestBuilder sameMajorOption(boolean v) { this.sameMajorOption = v; return this; }
		RequestBuilder importantOption(ImportantOption v) { this.importantOption = v; return this; }
		RequestBuilder minAgeOffset(Integer v) { this.minAgeOffset = v; return this; }
		RequestBuilder maxAgeOffset(Integer v) { this.maxAgeOffset = v; return this; }

		MatchingRequest build() {
			return new MatchingRequest(ageOption, mbtiOption, hobbyOption, contactFrequency,
				sameMajorOption, importantOption, minAgeOffset, maxAgeOffset);
		}
	}
}
