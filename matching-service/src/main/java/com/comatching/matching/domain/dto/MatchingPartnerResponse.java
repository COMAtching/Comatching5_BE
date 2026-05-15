package com.comatching.matching.domain.dto;

import java.util.List;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.member.ProfileTagDto;

import lombok.Builder;

@Builder
public record MatchingPartnerResponse(
	Long memberId,
	String nickname,
	Gender gender,
	int age,
	String mbti,
	String intro,
	String profileImageUrl,
	SocialAccountType socialType,
	String socialAccountId,
	String university,
	String major,
	String contactFrequency,
	String song,
	List<HobbyDto> hobbies,
	List<ProfileTagDto> tags
) {
	public static MatchingPartnerResponse from(ProfileResponse profile) {
		KoreanAge age = KoreanAge.fromBirthDate(profile.birthDate());
		return MatchingPartnerResponse.builder()
			.memberId(profile.memberId())
			.nickname(profile.nickname())
			.gender(profile.gender())
			.age(age != null ? age.getValue() : 0)
			.mbti(profile.mbti())
			.intro(profile.intro())
			.profileImageUrl(profile.profileImageUrl())
			.socialType(profile.socialType())
			.socialAccountId(profile.socialAccountId())
			.university(profile.university())
			.major(profile.major())
			.contactFrequency(profile.contactFrequency())
			.song(profile.song())
			.hobbies(profile.hobbies())
			.tags(profile.tags())
			.build();
	}
}
