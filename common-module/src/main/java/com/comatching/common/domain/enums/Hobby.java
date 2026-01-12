package com.comatching.common.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

@Getter
@RequiredArgsConstructor
public enum Hobby {
	// 운동 (SPORTS)
	SOCCER("축구", Category.SPORTS),
	GYM("헬스", Category.SPORTS),

	// 문화 (CULTURE)
	MOVIE("영화 감상", Category.CULTURE),
	READING("독서", Category.CULTURE),

	// 개발 (DEV)
	CODING("코딩", Category.DEV),

	// 여행 (TRAVEL)
	TRAVEL("여행", Category.TRAVEL),

	// 기타
	GAME("게임", Category.ETC),
	COOKING("요리", Category.ETC);

	private final String description;
	private final Category category;

	@Getter
	@RequiredArgsConstructor
	public enum Category {
		SPORTS("운동"),
		CULTURE("문화 예술"),
		DEV("개발"),
		TRAVEL("여행"),
		ETC("기타");

		private final String description;
	}

	// 카테고리로 취미 목록 찾기
	public static List<Hobby> getHobbiesByCategory(Category category) {
		return Arrays.stream(Hobby.values())
			.filter(h -> h.getCategory() == category)
			.toList();
	}
}