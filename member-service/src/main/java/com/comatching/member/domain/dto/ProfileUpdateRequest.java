package com.comatching.member.domain.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.dto.member.ProfileIntroDto;

public record ProfileUpdateRequest(
	String nickname,
	String intro,
	String mbti,
	String profileImageUrl,
	Gender gender,
	LocalDate birthDate,
	SocialAccountType socialType,
	String socialAccountId,
	Set<Hobby> hobbies,
	List<ProfileIntroDto> intros
) {}