package com.comatching.common.dto.member;

import com.comatching.common.domain.enums.IntroQuestion;

public record ProfileIntroDto(
	IntroQuestion question,
	String answer
) {
}
