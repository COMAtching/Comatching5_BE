package com.comatching.common.dto.member;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;
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
	String socialAccountId,
	Set<Hobby> hobbies,
	List<ProfileIntroDto> intros
) {}
