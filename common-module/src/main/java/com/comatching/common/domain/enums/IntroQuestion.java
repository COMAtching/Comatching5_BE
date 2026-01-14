package com.comatching.common.domain.enums;

import lombok.Getter;

@Getter
public enum IntroQuestion {

	HEIGHT("제 키는"),
	JOB("제 직업은"),
	DRINKING_HABIT("제 음주 습관은"),
	SMOKING_HABIT("저는 흡연을"),
	FAVORITE_FOOD("제가 좋아하는 음식은"),
	;

	private final String question;

	IntroQuestion(String question) {
		this.question = question;
	}
}
