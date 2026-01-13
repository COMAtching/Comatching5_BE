package com.comatching.common.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ItemRoute {

	CHARGE("충전"),
	EVENT("이벤트 획득"),
	REFUND("환불/복구");

	private final String description;
}
