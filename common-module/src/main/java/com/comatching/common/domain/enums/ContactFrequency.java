package com.comatching.common.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ContactFrequency {

	FREQUENT("자주", "자주 연락"),
	NORMAL("보통", "보통 연락"),
	RARE("적음", "적은 연락");

	private final String code;
	private final String description;
}
