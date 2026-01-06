package com.comatching.member.domain.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.member.domain.entity.Member;
import com.comatching.member.domain.entity.Profile;
import com.comatching.member.domain.repository.MemberRepository;
import com.comatching.member.domain.repository.ProfileRepository;
import com.comatching.member.global.exception.MemberErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ProfileServiceImpl implements ProfileService{

	private final MemberRepository memberRepository;
	private final ProfileRepository profileRepository;

	@Override
	public ProfileResponse createProfile(Long memberId, ProfileCreateRequest request) {

		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_EXIST));

		if (member.getProfile() != null) {
			throw new BusinessException(MemberErrorCode.PROFILE_ALREADY_EXISTS);
		}

		ProfileResponse profileResponse = saveProfile(request, member);

		member.upgradeRoleToUser();

		return profileResponse;
	}

	private ProfileResponse saveProfile(ProfileCreateRequest request, Member member) {
		Profile profile = Profile.builder()
			.member(member)
			.nickname(request.nickname())
			.gender(request.gender())
			.birthDate(request.birthDate())
			.mbti(request.mbti())
			.intro(request.intro())
			.profileImageUrl(request.profileImageUrl())
			.socialAccountType(request.socialType())
			.socialAccountId(request.socialAccountId())
			.build();

		Profile savedProfile = profileRepository.save(profile);

		return toProfileResponse(savedProfile);
	}

	private ProfileResponse toProfileResponse(Profile profile) {

		Member member = profile.getMember();

		return ProfileResponse.builder()
			.memberId(member.getId())
			.email(member.getEmail())
			.nickname(profile.getNickname())
			.gender(profile.getGender())
			.birthDate(profile.getBirthDate())
			.mbti(profile.getMbti())
			.intro(profile.getIntro())
			.profileImageUrl(profile.getProfileImageUrl())
			.socialType(profile.getSocialAccountType())
			.socialAccountId(profile.getSocialAccountId())
			.build();

	}
}
