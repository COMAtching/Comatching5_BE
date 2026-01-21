package com.comatching.matching.domain.dto;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.matching.domain.enums.AgeOption;

public record MatchingRequest(

	AgeOption ageOption,
	String mbtiOption,
	HobbyCategory hobbyOption,
	ContactFrequency contactFrequency,
	boolean sameMajorOption,
	String importantOption
) {
}
