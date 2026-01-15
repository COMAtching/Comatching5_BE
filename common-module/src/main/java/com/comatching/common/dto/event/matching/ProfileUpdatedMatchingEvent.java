package com.comatching.common.dto.event.matching;

import java.time.LocalDate;
import java.util.List;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;

import lombok.Builder;

@Builder
public record ProfileUpdatedMatchingEvent(
	Long memberId,
	Long profileId,
	Gender gender,
	String mbti,
	String major,
	List<Hobby> hobbies,
	LocalDate birthDate,
	Boolean isMatchable
) {
}
