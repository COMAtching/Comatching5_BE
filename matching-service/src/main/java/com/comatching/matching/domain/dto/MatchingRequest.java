package com.comatching.matching.domain.dto;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.matching.domain.enums.AgeOption;
import com.comatching.matching.domain.enums.ImportantOption;

public record MatchingRequest(

	AgeOption ageOption,
	String mbtiOption,
	HobbyCategory hobbyOption,
	ContactFrequency contactFrequency,
	boolean sameMajorOption,
	ImportantOption importantOption,
	Integer minAgeOffset,
	Integer maxAgeOffset
) {

	public MatchingRequest(
		AgeOption ageOption,
		String mbtiOption,
		HobbyCategory hobbyOption,
		ContactFrequency contactFrequency,
		boolean sameMajorOption,
		ImportantOption importantOption
	) {
		this(ageOption, mbtiOption, hobbyOption, contactFrequency, sameMajorOption, importantOption, null, null);
	}

	public boolean hasAgeLimit() {
		return minAgeOffset != null || maxAgeOffset != null;
	}

	public boolean hasCompleteAgeLimit() {
		return minAgeOffset != null && maxAgeOffset != null;
	}
}
