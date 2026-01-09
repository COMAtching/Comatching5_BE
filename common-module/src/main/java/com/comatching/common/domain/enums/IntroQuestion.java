package com.comatching.common.domain.enums;

public enum IntroQuestion {

	HEIGHT("키"),
	JOB("직업"),
	DRINKING_HABIT("음주 습관"),
	SMOKING_HABIT("흡연 여부"),
	FAVORITE_FOOD("좋아하는 음식"),
	;

	private final String question;

	IntroQuestion(String question) {
		this.question = question;
	}
}
