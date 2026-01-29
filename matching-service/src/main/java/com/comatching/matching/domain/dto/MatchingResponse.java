package com.comatching.matching.domain.dto;

import java.util.List;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileTagDto;
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
	List<ProfileTagDto> tags
) {
	public static MatchingResponse of(MatchingCandidate candidate, ProfileResponse profile) {
		return MatchingResponse.builder()
			.memberId(candidate.getMemberId())
			.gender(candidate.getGender())
			.age(candidate.getAge() != null ? candidate.getAge().getValue() : 0)
			.major(candidate.getMajor())
			.mbti(profile.mbti())
			.intro(profile.intro())
			.nickname(profile.nickname())
			.profileImageUrl(profile.profileImageUrl())
			.socialType(profile.socialType())
			.socialAccountId(profile.socialAccountId())
			.hobbies(profile.hobbies())
			.tags(profile.tags())
			.build();
	}
}
