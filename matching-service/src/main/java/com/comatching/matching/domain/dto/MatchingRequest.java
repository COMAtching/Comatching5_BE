package com.comatching.matching.domain.dto;

import com.comatching.common.domain.enums.Hobby;
import com.comatching.matching.domain.enums.AgeOption;

public record MatchingRequest(

	AgeOption ageOption,
	String mbtiOption,
	Hobby.Category hobbyOption,
	boolean sameMajorOption,
	String importantOption
) {
}
