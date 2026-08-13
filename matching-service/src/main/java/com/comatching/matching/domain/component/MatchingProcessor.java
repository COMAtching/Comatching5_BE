package com.comatching.matching.domain.component;

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
import com.comatching.matching.domain.repository.candidate.MatchingCandidateSearchCondition;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.global.exception.MatchingErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * == importantOption 이 하는 일 ==
 * 사용자가 고른 '가장 중요한 조건' 하나만 필수(WHERE)가 되고, 나머지 옵션은
 * 점수(ORDER BY)로만 반영된다. 그래서 조건을 두 갈래로 나눠서 넘긴다.
 * <p>
 * importantOption | 필수가 되는 것              | 점수에만 반영되는 것
 * ----------------|-----------------------------|----------------------------
 * MBTI            | MBTI 글자 전부 포함          | 취미, 나이, 연락빈도
 * HOBBY           | 해당 취미 보유               | MBTI, 나이, 연락빈도
 * CONTACT         | 연락빈도 일치                | MBTI, 취미, 나이
 * AGE             | 나이 조건(EQUAL/OLDER/YOUNGER) | MBTI, 취미, 연락빈도
 * null            | 없음                        | 전부
 * <p>
 * 점수 쪽은 importantOption 과 무관하게 설정된 옵션을 전부 반영한다.
 * 필수로 걸린 조건도 점수에 또 들어가는데, 그건 원본
 * DefaultMatchingScoreCalculator 가 그렇게 동작했기 때문이다.
 * (어차피 전원이 그 조건을 통과했으므로 모두 같은 점수를 더 받아 순위에는 영향이 없다.)
 * <p>
 * minAgeOffset / maxAgeOffset(나이 제한)은 importantOption 과 별개로 항상 필수다.
 * <p>
 * == 개선 전과 달라진 점 ==
 * 예전에는 조건에 맞는 후보를 500 개씩 100 페이지로 전부 가져와서 자바에서
 * 점수를 매기고 최고점 그룹을 모은 뒤 랜덤으로 골랐다.
 * 이제 점수 계산과 최고점 선택이 전부 SQL 에서 끝나 쿼리 한 번이면 된다.
 */

@Component
@RequiredArgsConstructor
public class MatchingProcessor {

    private static final int MAX_ALLOWED_AGE = 27;

    private final MatchingCandidateRepository candidateRepository;
    private final MatchingHistoryRepository historyRepository;

    public MatchingCandidate process(Long memberId, ProfileResponse myProfile, MatchingRequest request) {
        KoreanAge myAge = KoreanAge.fromBirthDate(myProfile.birthDate());

        // importantOption 이 AGE 인데 ageOption 이나 내 나이가 없으면 false 를 돌려 모든 후보를 탈락
        if (request.importantOption() == ImportantOption.AGE
                && (request.ageOption() == null || myAge == null)) {
            throw new BusinessException(MatchingErrorCode.NO_MATCHING_CANDIDATE);
        }

        List<Long> excludeMemberIds = historyRepository.findMatchedMemberIdsByMemberId(memberId);

        return candidateRepository
                .findBestCandidate(buildCondition(myProfile, request, myAge, excludeMemberIds))
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.NO_MATCHING_CANDIDATE));
    }

    private MatchingCandidateSearchCondition buildCondition(
            ProfileResponse myProfile, MatchingRequest request, KoreanAge myAge, List<Long> excludeMemberIds) {

        Gender targetGender = (myProfile.gender() == Gender.MALE) ? Gender.FEMALE : Gender.MALE;

        return new MatchingCandidateSearchCondition(
                // ---------- 필수 조건 (WHERE) ----------
                targetGender,
                request.sameMajorOption() ? myProfile.major() : null,
                excludeMemberIds,
                minAge(request, myAge),
                maxAge(request, myAge),
                requiredMbtiTraits(request),
                requiredContactFrequency(request),
                requiredHobbyCategory(request),

                // ---------- 점수 (ORDER BY) ----------
                // importantOption 과 무관하게 설정된 값을 그대로 넘긴다.
                myAge != null ? myAge.getValue() : null,
                request.mbtiOption(),
                request.hobbyOption(),
                request.ageOption(),
                request.contactFrequency()
        );
    }

    private String requiredMbtiTraits(MatchingRequest request) {
        if (request.importantOption() != ImportantOption.MBTI
                || request.mbtiOption() == null
                || request.mbtiOption().isBlank()) {
            return null;
        }
        return request.mbtiOption().toUpperCase(Locale.ROOT);
    }

    private ContactFrequency requiredContactFrequency(MatchingRequest request) {
        return request.importantOption() == ImportantOption.CONTACT ? request.contactFrequency() : null;
    }

    private HobbyCategory requiredHobbyCategory(MatchingRequest request) {
        return request.importantOption() == ImportantOption.HOBBY ? request.hobbyOption() : null;
    }

    private Integer minAge(MatchingRequest request, KoreanAge myAge) {
        Integer minAge = null;

        if (request.hasCompleteAgeLimit()) {
            minAge = request.minAgeLimit();
        }
        if (request.importantOption() == ImportantOption.AGE && myAge != null) {
            if (request.ageOption() == AgeOption.EQUAL) {
                minAge = max(minAge, myAge.getValue());
            } else if (request.ageOption() == AgeOption.OLDER) {
                minAge = max(minAge, myAge.getValue() + 1);
            }
        }
        return minAge;
    }

    private Integer maxAge(MatchingRequest request, KoreanAge myAge) {
        Integer maxAge = null;

        if (request.hasCompleteAgeLimit()) {
            maxAge = Math.min(MAX_ALLOWED_AGE, request.maxAgeLimit());
        }
        if (request.importantOption() == ImportantOption.AGE && myAge != null) {
            if (request.ageOption() == AgeOption.EQUAL) {
                maxAge = min(maxAge, myAge.getValue());
            } else if (request.ageOption() == AgeOption.YOUNGER) {
                maxAge = min(maxAge, myAge.getValue() - 1);
            }
        }
        return maxAge;
    }

    private Integer max(Integer current, int candidate) {
        return current == null ? candidate : Math.max(current, candidate);
    }

    private Integer min(Integer current, int candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }
}
