package com.comatching.common.dto.member;

import java.time.LocalDate;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.SocialAccountType;

import lombok.Builder;

@Builder
public record ProfileResponse(
	Long memberId,
	String email,
	String nickname,
	Gender gender,
	LocalDate birthDate,
	String mbti,
	String intro,
	String profileImageUrl,
	SocialAccountType socialType,
	String socialAccountId
) {
}
