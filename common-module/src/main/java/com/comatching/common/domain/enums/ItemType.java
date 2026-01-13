package com.comatching.common.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ItemType {
	MATCHING_TICKET("매칭권"),
	;

	private final String description;
}
