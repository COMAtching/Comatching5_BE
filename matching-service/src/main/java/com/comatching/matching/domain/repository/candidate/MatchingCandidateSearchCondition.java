package com.comatching.matching.domain.repository.candidate;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.matching.domain.enums.AgeOption;

import java.util.List;

public record MatchingCandidateSearchCondition(
        // --- 표본 추출 ---
        int randomStart,
        int sampleSize,

        // --- 필수 조건 ---
        Gender targetGender,
        String excludeMajor,
        List<Long> excludeMemberIds,
        Integer minAge,
        Integer maxAge,
        String requiredMbtiTraits,
        ContactFrequency requiredContactFrequency,
        HobbyCategory requiredHobbyCategory,

        // --- 점수 ---
        Integer myAge,
        String scoreMbtiTraits,
        HobbyCategory scoreHobbyCategory,
        AgeOption scoreAgeOption,
        ContactFrequency scoreContactFrequency
) {

    /** randomStart 만 바꾼 사본. 표본 창이 비었을 때 처음부터 다시 훑기 위해 쓴다. */
    public MatchingCandidateSearchCondition withRandomStart(int newRandomStart) {
        return new MatchingCandidateSearchCondition(
                newRandomStart, sampleSize, targetGender, excludeMajor, excludeMemberIds,
                minAge, maxAge, requiredMbtiTraits, requiredContactFrequency, requiredHobbyCategory,
                myAge, scoreMbtiTraits, scoreHobbyCategory, scoreAgeOption, scoreContactFrequency);
    }
}
