package com.comatching.matching.domain.dto;

import com.comatching.common.exception.BusinessException;
import com.comatching.matching.global.exception.MatchingErrorCode;
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

	/** Matching preferences allow one choice per axis and at most two choices. */
	public void validateMbtiPreference() {
		if (mbtiOption == null || mbtiOption.isEmpty()) return;
		int axes = 0;
		if (mbtiOption.length() > 2) throw invalidMbti();
		for (char trait : mbtiOption.toCharArray()) {
			int axis = switch (trait) {
				case 'E', 'I' -> 1;
				case 'S', 'N' -> 2;
				case 'T', 'F' -> 4;
				case 'J', 'P' -> 8;
				default -> 0;
			};
			if (axis == 0 || (axes & axis) != 0) throw invalidMbti();
			axes |= axis;
		}
	}

	private static BusinessException invalidMbti() {
		return new BusinessException(MatchingErrorCode.INVALID_MBTI_PREFERENCE);
	}

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

	public Integer minAgeLimit() {
		return minAgeOffset;
	}

	public Integer maxAgeLimit() {
		return maxAgeOffset;
	}
}
