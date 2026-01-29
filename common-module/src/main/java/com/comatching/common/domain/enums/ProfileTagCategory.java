package com.comatching.common.domain.enums;

import lombok.Getter;

@Getter
public enum ProfileTagCategory {

	APPEARANCE("외모"),
	BODY("체형"),
	PERSONALITY("성격"),
	CHARM("매력");

	private final String label;

	ProfileTagCategory(String label) {
		this.label = label;
	}
}
