package com.comatching.matching.domain.dto;

import java.util.List;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileIntroDto;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.matching.domain.entity.MatchingCandidate;

import lombok.Builder;

@Builder
public record MatchingResponse(
	Long memberId,
	Gender gender,
	int age,
	String mbti,
	String major,
	String intro,
	String nickname,
	String profileImageUrl,
	SocialAccountType socialType,
	String socialAccountId,
	List<HobbyDto> hobbies,
	List<ProfileIntroDto> intros
) {
	public static MatchingResponse of(MatchingCandidate candidate, ProfileResponse profile) {
		return MatchingResponse.builder()
			.memberId(candidate.getMemberId())
			.gender(candidate.getGender())
			.age(candidate.getAge())
			.major(candidate.getMajor())
			.mbti(profile.mbti())
			.intro(profile.intro())
			.nickname(profile.nickname())
			.profileImageUrl(profile.profileImageUrl())
			.socialType(profile.socialType())
			.socialAccountId(profile.socialAccountId())
			.hobbies(profile.hobbies())
			.intros(profile.intros())
			.build();
	}
}
