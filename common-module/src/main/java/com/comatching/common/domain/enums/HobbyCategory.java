package com.comatching.common.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum HobbyCategory {
	SPORTS("운동"),
	CULTURE("문화 예술"),
	DEV("개발"),
	TRAVEL("여행"),
	ETC("기타");

	private final String description;
}
