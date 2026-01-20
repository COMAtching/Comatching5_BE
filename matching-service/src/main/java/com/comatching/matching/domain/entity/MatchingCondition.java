package com.comatching.matching.domain.entity;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Hobby;
import com.comatching.matching.domain.enums.AgeOption;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchingCondition {

	@Enumerated(EnumType.STRING)
	private AgeOption ageOption;

	@Enumerated(EnumType.STRING)
	private ContactFrequency contactFrequency;

	@Enumerated(EnumType.STRING)
	private Hobby.Category hobbyOption;

	private Boolean sameMajorOption;

	private String mbtiOption;

	@Builder

	public MatchingCondition(AgeOption ageOption, ContactFrequency contactFrequency, Hobby.Category hobbyOption,
		Boolean sameMajorOption, String mbtiOption) {
		this.ageOption = ageOption;
		this.contactFrequency = contactFrequency;
		this.hobbyOption = hobbyOption;
		this.sameMajorOption = sameMajorOption;
		this.mbtiOption = mbtiOption;
	}
}
