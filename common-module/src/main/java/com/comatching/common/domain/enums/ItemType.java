package com.comatching.common.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ItemType {
	MATCHING_TICKET("매칭권", "기본 옵션만으로 매칭을 할 수 있는 아이템"),
	OPTION_TICKET("옵션권", "같은 학과 제외, 중요 옵션 선택이 가능해지는 아이템")
	;

	private final String name;
	private final String description;
}
