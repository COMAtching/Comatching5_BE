package com.comatching.user.domain.member.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.comatching.common.domain.enums.ProfileTagItem;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.common.dto.event.member.MemberUpdateEvent;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.member.ProfileTagDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.service.S3Service;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.dto.ProfileUpdateRequest;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.entity.ProfileHobby;
import com.comatching.user.domain.member.entity.ProfileTag;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.domain.member.repository.ProfileRepository;
import com.comatching.user.global.config.ProfileImageProperties;
import com.comatching.user.global.exception.UserErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ProfileServiceImpl implements ProfileCreateService, ProfileManageService {

	private static final String DEFAULT_IMAGE_VALUE = "default";
	private static final String DEFAULT_IMAGE_PREFIX = "default_";
	private static final String DEFAULT_IMAGE_EXTENSION = ".png";
	private static final Set<String> DEFAULT_IMAGE_ANIMALS = Set.of(
		"dog", "cat", "dinosaur", "otter", "bear", "fox", "penguin", "wolf", "rabbit", "snake", "horse", "frog"
	);

	private final MemberRepository memberRepository;
	private final ProfileRepository profileRepository;
	private final UserEventPublisher eventPublisher;
	private final ProfileImageProperties profileImageProperties;
	private final S3Service s3Service;

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
	public boolean isNicknameAvailable(String nickname) {
		String normalizedNickname = normalizeNickname(nickname);
		return !profileRepository.existsByNickname(normalizedNickname);
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

		String normalizedNickname = normalizeNicknameForUpdate(request.nickname(), profile.getNickname(), memberId);
		String profileImageUrl = resolveProfileImageUrlForUpdate(request.profileImageUrl());

		profile.update(
			normalizedNickname,
			request.intro(),
			request.mbti(),
			profileImageUrl,
			request.gender(),
			request.birthDate(),
			request.socialType(),
			request.socialAccountId(),
			request.university(),
			request.major(),
			request.contactFrequency(),
			request.song(),
			getProfileHobbies(request.hobbies()),
			getProfileTags(request.tags()),
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

		String finalNickname = normalizeNickname(request.nickname());
		validateNicknameDuplicateOnCreate(finalNickname);
		String finalProfileImageUrl = resolveProfileImageUrl(request.profileImageKey());

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
			.tags(getProfileTags(request.tags()))
			.build();

		return profileRepository.save(profile);
	}

	private String normalizeNickname(String nickname) {
		if (!StringUtils.hasText(nickname)) {
			throw new BusinessException(UserErrorCode.INVALID_NICKNAME);
		}

		return nickname.trim();
	}

	private String normalizeNicknameForUpdate(String nickname, String currentNickname, Long memberId) {
		if (nickname == null) {
			return null;
		}

		String normalizedNickname = normalizeNickname(nickname);
		if (!Objects.equals(normalizedNickname, currentNickname)
			&& profileRepository.existsByNicknameAndMemberIdNot(normalizedNickname, memberId)) {
			throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
		}

		return normalizedNickname;
	}

	private void validateNicknameDuplicateOnCreate(String nickname) {
		if (profileRepository.existsByNickname(nickname)) {
			throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
		}
	}

	private String resolveProfileImageUrlForUpdate(String profileImageValue) {
		if (profileImageValue == null) {
			return null;
		}
		return resolveProfileImageUrl(profileImageValue);
	}

	private String resolveProfileImageUrl(String profileImageValue) {
		if (!StringUtils.hasText(profileImageValue)) {
			return buildDefaultProfileImageUrl(DEFAULT_IMAGE_VALUE + DEFAULT_IMAGE_EXTENSION);
		}

		String normalizedValue = profileImageValue.trim();
		String loweredValue = normalizedValue.toLowerCase(Locale.ROOT);

		if (DEFAULT_IMAGE_VALUE.equals(loweredValue)) {
			return buildDefaultProfileImageUrl(DEFAULT_IMAGE_VALUE + DEFAULT_IMAGE_EXTENSION);
		}

		if (loweredValue.startsWith(DEFAULT_IMAGE_PREFIX)) {
			String animalName = loweredValue.substring(DEFAULT_IMAGE_PREFIX.length());
			if (DEFAULT_IMAGE_ANIMALS.contains(animalName)) {
				return buildDefaultProfileImageUrl(animalName + DEFAULT_IMAGE_EXTENSION);
			}
		}

		if (normalizedValue.startsWith("http://") || normalizedValue.startsWith("https://")) {
			return normalizedValue;
		}

		return s3Service.getFileUrl(normalizedValue);
	}

	private String buildDefaultProfileImageUrl(String filename) {
		if (!StringUtils.hasText(profileImageProperties.baseUrl())) {
			return null;
		}
		return profileImageProperties.baseUrl() + filename;
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

	private static List<ProfileTag> getProfileTags(List<ProfileTagDto> tags) {
		List<ProfileTag> newTags = null;
		if (tags != null) {
			newTags = tags.stream()
				.map(dto -> new ProfileTag(ProfileTagItem.valueOf(dto.tag())))
				.toList();
		}
		return newTags;
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
			.tags(profile.getTags().stream()
				.map(t -> new ProfileTagDto(t.getTag().name()))
				.toList())
			.build();

	}
}
