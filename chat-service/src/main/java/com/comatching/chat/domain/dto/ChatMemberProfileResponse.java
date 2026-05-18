package com.comatching.chat.domain.dto;

import java.util.List;

import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.common.dto.matching.MatchingHistoryReferenceResponse;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.member.ProfileTagDto;

public record ChatMemberProfileResponse(
	Long memberId,
	String nickname,
	String profileImageUrl,
	String major,
	Integer age,
	String mbti,
	String contactFrequency,
	List<HobbyDto> hobbies,
	List<ProfileTagDto> tags,
	String song,
	String intro,
	SocialAccountType socialType,
	String socialAccountId,
	Long historyId,
	boolean favorite
) {
	public static ChatMemberProfileResponse from(
		ProfileResponse profile,
		MatchingHistoryReferenceResponse historyReference
	) {
		KoreanAge age = KoreanAge.fromBirthDate(profile.birthDate());

		return new ChatMemberProfileResponse(
			profile.memberId(),
			profile.nickname(),
			profile.profileImageUrl(),
			profile.major(),
			age != null ? age.getValue() : null,
			profile.mbti(),
			profile.contactFrequency(),
			profile.hobbies() != null ? profile.hobbies() : List.of(),
			profile.tags() != null ? profile.tags() : List.of(),
			profile.song(),
			profile.intro(),
			profile.socialType(),
			profile.socialAccountId(),
			historyReference != null ? historyReference.historyId() : null,
			historyReference != null && historyReference.favorite()
		);
	}
}
