package com.comatching.member.domain.dto;

import java.time.LocalDate;
import java.util.List;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.dto.member.HobbyDto;
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
	String university,
	String major,
	ContactFrequency contactFrequency,
	String song,
	List<HobbyDto> hobbies,
	List<ProfileIntroDto> intros,
	Boolean isMatchable
) {}