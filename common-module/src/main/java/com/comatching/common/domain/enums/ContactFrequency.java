package com.comatching.common.domain.enums;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;

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

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static ContactFrequency from(String value) {
		if (value == null) {
			return null;
		}

		String normalizedValue = value.trim();
		if (normalizedValue.isEmpty()) {
			throw new IllegalArgumentException("Contact frequency value is blank");
		}

		return Arrays.stream(values())
			.filter(frequency -> frequency.name().equalsIgnoreCase(normalizedValue)
				|| frequency.code.equals(normalizedValue)
				|| frequency.description.equals(normalizedValue))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown contact frequency: " + value));
	}
}
