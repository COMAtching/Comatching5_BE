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
}
