package com.comatching.common.domain.enums;

public enum Hobby {
	SOCCER("축구"),
	MOVIE("영화 감상"),
	CODING("코딩"),
	READING("독서"),
	GYM("헬스"),
	TRAVEL("여행"),
	GAME("게임"),
	COOKING("요리"),
	;

	private final String description;

	Hobby(String description) {
		this.description = description;
	}
}
