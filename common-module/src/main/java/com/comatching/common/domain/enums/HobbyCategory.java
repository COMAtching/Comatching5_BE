package com.comatching.common.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum HobbyCategory {
	SPORTS("스포츠"),
	CULTURE("문화예술"),
	MUSIC("음악"),
	LEISURE("여가생활"),
	DAILY("일상/공부"),
	GAME("게임");

	private final String description;
}
