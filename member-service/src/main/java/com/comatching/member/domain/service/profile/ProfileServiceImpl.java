package com.comatching.member.domain.service.profile;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.common.dto.event.member.MemberUpdateEvent;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileIntroDto;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.member.domain.dto.ProfileUpdateRequest;
import com.comatching.member.domain.entity.Member;
import com.comatching.member.domain.entity.Profile;
import com.comatching.member.domain.entity.ProfileIntro;
import com.comatching.member.domain.repository.MemberRepository;
import com.comatching.member.domain.repository.ProfileRepository;
import com.comatching.member.global.exception.MemberErrorCode;
import com.comatching.member.infra.kafka.MemberEventProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Transactional
public class ProfileServiceImpl implements ProfileCreateService, ProfileManageService {

	private final MemberRepository memberRepository;
	private final ProfileRepository profileRepository;
	private final MemberEventProducer memberEventProducer;

	@Override
	public ProfileResponse createProfile(Long memberId, ProfileCreateRequest request) {

		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_EXIST));

		if (member.getProfile() != null) {
			throw new BusinessException(MemberErrorCode.PROFILE_ALREADY_EXISTS);
		}

		Profile profile = saveProfile(request, member);
		ProfileResponse profileResponse = toProfileResponse(profile);

		member.upgradeRoleToUser();

		publishMatchingEvent(profile, true);
		memberEventProducer.sendSignupEvent(profileResponse);

		return profileResponse;
	}

	@Override
	@Transactional(readOnly = true)
	public ProfileResponse getProfile(Long memberId) {
		Profile profile = profileRepository.findByMemberId(memberId)
			.orElseThrow(() -> new BusinessException(MemberErrorCode.PROFILE_NOT_EXISTS));

		return toProfileResponse(profile);
	}

	@Override
	public ProfileResponse updateProfile(Long memberId, ProfileUpdateRequest request) {
		Profile profile = profileRepository.findByMemberId(memberId)
			.orElseThrow(() -> new BusinessException(MemberErrorCode.PROFILE_NOT_EXISTS));

		profile.update(
			request.nickname(),
			request.intro(),
			request.mbti(),
			request.profileImageUrl(),
			request.gender(),
			request.birthDate(),
			request.socialType(),
			request.socialAccountId(),
			request.university(),
			request.major(),
			request.hobbies(),
			getProfileIntros(request.intros())
		);

		publishMatchingEvent(profile, true);

		Member member = profile.getMember();

		MemberUpdateEvent event = MemberUpdateEvent.builder()
			.memberId(member.getId())
			.nickname(profile.getNickname())
			.profileImageUrl(profile.getProfileImageUrl())
			.status(member.getStatus())
			.build();

		memberEventProducer.sendUpdateEvent(event);

		return toProfileResponse(profile);
	}

	private void publishMatchingEvent(Profile profile, boolean isMatchable) {
		ProfileUpdatedMatchingEvent event = ProfileUpdatedMatchingEvent.builder()
			.memberId(profile.getMember().getId())
			.profileId(profile.getId())
			.gender(profile.getGender())
			.mbti(profile.getMbti())
			.major(profile.getMajor())
			.hobbies(profile.getHobbies())
			.birthDate(profile.getBirthDate())
			.isMatchable(isMatchable)
			.build();

		memberEventProducer.sendProfileUpdatedMatchingEvent(event);
	}

	private Profile saveProfile(ProfileCreateRequest request, Member member) {
		Profile profile = Profile.builder()
			.member(member)
			.nickname(request.nickname())
			.gender(request.gender())
			.birthDate(request.birthDate())
			.mbti(request.mbti())
			.intro(request.intro())
			.profileImageUrl(request.profileImageKey())
			.socialAccountType(request.socialType())
			.socialAccountId(request.socialAccountId())
			.university(request.university())
			.major(request.major())
			.hobbies(request.hobbies())
			.intros(getProfileIntros(request.intros()))
			.build();

		Profile savedProfile = profileRepository.save(profile);

		return savedProfile;
	}

	private static List<ProfileIntro> getProfileIntros(List<ProfileIntroDto> intros) {
		List<ProfileIntro> newIntros = null;
		if (intros != null) {
			newIntros = intros.stream()
				.map(dto -> new ProfileIntro(dto.question(), dto.answer()))
				.toList();
		}
		return newIntros;
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
			.university(profile.getUniversity())
			.major(profile.getMajor())
			.hobbies(profile.getHobbies())
			.intros(profile.getIntros().stream()
				.map(i -> new ProfileIntroDto(i.getQuestion(), i.getAnswer()))
				.toList())
			.build();

	}
}
