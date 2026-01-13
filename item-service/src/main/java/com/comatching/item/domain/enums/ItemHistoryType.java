package com.comatching.item.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ItemHistoryType {
	CHARGE("충전"),
	EVENT("이벤트 획득"),
	USE("사용"),
	REFUND("환불/복구"),
	EXPIRED("기간 만료");

	private final String description;
}
