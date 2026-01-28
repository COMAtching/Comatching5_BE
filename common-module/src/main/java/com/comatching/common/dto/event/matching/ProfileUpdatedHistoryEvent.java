package com.comatching.common.dto.event.matching;

import java.time.LocalDate;
import java.util.List;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileIntroDto;

import lombok.Builder;

@Builder
public record ProfileUpdatedHistoryEvent(
	Long memberId,
	Long profileId,
	String nickname,
	Gender gender,
	String mbti,
	String major,
	List<HobbyDto> hobbies,
	String intro,
	List<ProfileIntroDto> intros,
	LocalDate birthDate,
	String profileImageUrl,
	String socialType,
	String socialAccountId,
	boolean isMatchable
) {}
