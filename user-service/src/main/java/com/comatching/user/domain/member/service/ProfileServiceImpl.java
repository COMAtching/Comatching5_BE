package com.comatching.user.domain.member.service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.domain.enums.IntroQuestion;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.common.dto.event.member.MemberUpdateEvent;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileIntroDto;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.component.RandomNicknameGenerator;
import com.comatching.user.domain.member.dto.ProfileUpdateRequest;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.entity.ProfileHobby;
import com.comatching.user.domain.member.entity.ProfileIntro;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.domain.member.repository.ProfileRepository;
import com.comatching.user.global.config.ProfileImageProperties;
import com.comatching.user.global.exception.UserErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ProfileServiceImpl implements ProfileCreateService, ProfileManageService {

	private final MemberRepository memberRepository;
	private final ProfileRepository profileRepository;
	private final UserEventPublisher eventPublisher;
	private final ProfileImageProperties profileImageProperties;
	private final RandomNicknameGenerator nicknameGenerator;

	@Override
	public ProfileResponse createProfile(Long memberId, ProfileCreateRequest request) {

		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_EXIST));

		if (member.getProfile() != null) {
			throw new BusinessException(UserErrorCode.PROFILE_ALREADY_EXISTS);
		}

		Profile profile = saveProfile(request, member);
		ProfileResponse profileResponse = toProfileResponse(profile);

		member.upgradeRoleToUser();

		publishMatchingEvent(profile);
		eventPublisher.sendSignupEvent(profileResponse);

		return profileResponse;
	}

	@Override
	@Transactional(readOnly = true)
	public ProfileResponse getProfile(Long memberId) {
		Profile profile = profileRepository.findByMemberId(memberId)
			.orElseThrow(() -> new BusinessException(UserErrorCode.PROFILE_NOT_EXISTS));

		return toProfileResponse(profile);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ProfileResponse> getProfilesByIds(List<Long> memberIds) {
		if (memberIds == null || memberIds.isEmpty()) {
			return List.of();
		}

		return profileRepository.findAllByMemberIdIn(memberIds).stream()
			.map(this::toProfileResponse)
			.toList();
	}

	@Override
	public ProfileResponse updateProfile(Long memberId, ProfileUpdateRequest request) {
		Profile profile = profileRepository.findByMemberId(memberId)
			.orElseThrow(() -> new BusinessException(UserErrorCode.PROFILE_NOT_EXISTS));

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
			request.contactFrequency(),
			request.song(),
			getProfileHobbies(request.hobbies()),
			getProfileIntros(request.intros()),
			request.isMatchable()
		);

		publishMatchingEvent(profile);

		Member member = profile.getMember();

		MemberUpdateEvent event = MemberUpdateEvent.builder()
			.memberId(member.getId())
			.nickname(profile.getNickname())
			.profileImageUrl(profile.getProfileImageUrl())
			.status(member.getStatus())
			.build();

		eventPublisher.sendUpdateEvent(event);

		return toProfileResponse(profile);
	}

	private void publishMatchingEvent(Profile profile) {
		ProfileUpdatedMatchingEvent event = ProfileUpdatedMatchingEvent.builder()
			.memberId(profile.getMember().getId())
			.profileId(profile.getId())
			.gender(profile.getGender())
			.mbti(profile.getMbti())
			.major(profile.getMajor())
			.contactFrequency(profile.getContactFrequency())
			.hobbyCategories(profile.getHobbyCategories())
			.birthDate(profile.getBirthDate())
			.isMatchable(profile.isMatchable())
			.build();

		eventPublisher.sendProfileUpdatedMatchingEvent(event);
	}

	private Profile saveProfile(ProfileCreateRequest request, Member member) {

		String finalProfileImageUrl = resolveProfileImageUrl(request.profileImageKey());

		String finalNickname = request.nickname();
		if (!StringUtils.hasText(finalNickname)) {
			finalNickname = nicknameGenerator.generate();
		}

		Profile profile = Profile.builder()
			.member(member)
			.nickname(finalNickname)
			.gender(request.gender())
			.birthDate(request.birthDate())
			.mbti(request.mbti())
			.intro(request.intro())
			.profileImageUrl(finalProfileImageUrl)
			.socialAccountType(request.socialType())
			.socialAccountId(request.socialAccountId())
			.university(request.university())
			.major(request.major())
			.contactFrequency(request.contactFrequency())
			.song(request.song())
			.hobbies(getProfileHobbies(request.hobbies()))
			.intros(getProfileIntros(request.intros()))
			.build();

		return profileRepository.save(profile);
	}

	private String resolveProfileImageUrl(String inputImageKey) {
		if (StringUtils.hasText(inputImageKey)) {
			return profileImageProperties.baseUrl() + inputImageKey;
		}

		List<String> defaults = profileImageProperties.filenames();
		if (defaults == null || defaults.isEmpty()) {
			return null;
		}

		int randomIndex = ThreadLocalRandom.current().nextInt(defaults.size());
		String selectedFilename = defaults.get(randomIndex);

		return profileImageProperties.baseUrl() + selectedFilename;
	}

	private static List<ProfileHobby> getProfileHobbies(List<HobbyDto> hobbies) {
		List<ProfileHobby> newHobbies = null;
		if (hobbies != null) {
			newHobbies = hobbies.stream()
				.map(dto -> new ProfileHobby(dto.category(), dto.name()))
				.toList();
		}
		return newHobbies;
	}

	private static List<ProfileIntro> getProfileIntros(List<ProfileIntroDto> intros) {
		List<ProfileIntro> newIntros = null;
		if (intros != null) {
			newIntros = intros.stream()
				.map(dto -> new ProfileIntro(IntroQuestion.valueOf(dto.question()), dto.answer()))
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
			.contactFrequency(profile.getContactFrequency().getCode())
			.song(profile.getSong())
			.hobbies(profile.getHobbies().stream()
				.map(h -> new HobbyDto(h.getCategory(), h.getName()))
				.toList())
			.intros(profile.getIntros().stream()
				.map(i -> new ProfileIntroDto(i.getQuestion().getQuestion(), i.getAnswer()))
				.toList())
			.build();

	}
}
