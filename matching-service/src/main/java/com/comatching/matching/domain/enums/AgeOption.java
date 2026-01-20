package com.comatching.matching.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AgeOption {

	OLDER("연상"),
	YOUNGER("연하"),
	EQUAL("동갑");

	private final String description;
}