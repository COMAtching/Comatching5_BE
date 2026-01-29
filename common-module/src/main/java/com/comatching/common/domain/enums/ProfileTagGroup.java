package com.comatching.common.domain.enums;

import static com.comatching.common.domain.enums.ProfileTagCategory.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.Getter;

@Getter
public enum ProfileTagGroup {

	// 외모
	FACE_SHAPE(APPEARANCE, "얼굴형"),
	FACE_POINT(APPEARANCE, "포인트"),
	SKIN(APPEARANCE, "피부/인상"),
	EYE_LIP(APPEARANCE, "눈/입술"),

	// 체형
	BODY_TYPE(BODY, "체형"),
	EXERCISE(BODY, "운동"),
	BODY_FEATURE(BODY, "체형 특징"),

	// 성격
	ENERGY(PERSONALITY, "에너지/분위기"),
	EXPRESSION(PERSONALITY, "표현/커뮤니케이션"),
	ATTITUDE(PERSONALITY, "태도/가치관"),
	THINKING(PERSONALITY, "사고방식/일처리"),

	// 매력
	TALENT(CHARM, "잘하는 것");

	private final ProfileTagCategory category;
	private final String label;

	private static final Map<ProfileTagCategory, List<ProfileTagGroup>> BY_CATEGORY;

	static {
		BY_CATEGORY = Collections.unmodifiableMap(
			Arrays.stream(values())
				.collect(Collectors.groupingBy(
					ProfileTagGroup::getCategory,
					() -> new EnumMap<>(ProfileTagCategory.class),
					Collectors.toUnmodifiableList()
				))
		);
	}

	ProfileTagGroup(ProfileTagCategory category, String label) {
		this.category = category;
		this.label = label;
	}

	public static List<ProfileTagGroup> getByCategory(ProfileTagCategory category) {
		return BY_CATEGORY.getOrDefault(category, List.of());
	}
}
