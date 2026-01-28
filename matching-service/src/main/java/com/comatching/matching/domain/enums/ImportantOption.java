package com.comatching.matching.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ImportantOption {

	AGE("나이"),
	MBTI("MBTI"),
	HOBBY("취미"),
	CONTACT("연락 빈도");

	private final String description;
}
