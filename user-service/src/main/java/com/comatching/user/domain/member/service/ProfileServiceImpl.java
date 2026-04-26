package com.comatching.user.domain.member.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import com.comatching.common.domain.enums.Gender;
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
	private static final String DEFAULT_MALE_IMAGE_FILENAME = "dog_male 1.png";
	private static final String DEFAULT_FEMALE_IMAGE_FILENAME = "cat_female 1.png";
	private static final String DEFAULT_NEUTRAL_IMAGE_FILENAME = "dinosaur 1.png";

	private static final Map<String, String> MALE_ANIMAL_IMAGE_FILENAMES = Map.of(
		"dog", "dog_male 1.png",
		"cat", "cat_male 1.png",
		"bear", "bear_male 1.png",
		"fox", "fox_male 1.png",
		"rabbit", "rabbit_male 1.png",
		"otter", "otter_male 1.png",
		"wolf", "Wolf_male 1.png",
		"horse", "horse_male.png"
	);

	private static final Map<String, String> FEMALE_ANIMAL_IMAGE_FILENAMES = Map.of(
		"dog", "dog_female 1.png",
		"cat", "cat_female 1.png",
		"bear", "bear_female 1.png",
		"fox", "fox_female 1.png",
		"rabbit", "rabbit_female 1.png",
		"otter", "otter_female 1.png",
		"wolf", "Wolf_female 1.png",
		"snake", "snake_female.png"
	);

	private static final Map<String, String> NEUTRAL_ANIMAL_IMAGE_FILENAMES = Map.of(
		"dinosaur", "dinosaur 1.png"
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
		Gender effectiveGender = request.gender() != null ? request.gender() : profile.getGender();
		String profileImageUrl = resolveProfileImageUrlForUpdate(request.profileImageUrl(), effectiveGender);

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
		String finalProfileImageUrl = resolveProfileImageUrl(request.profileImageKey(), request.gender());

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

	private String resolveProfileImageUrlForUpdate(String profileImageValue, Gender effectiveGender) {
		if (profileImageValue == null) {
			return null;
		}
		return resolveProfileImageUrl(profileImageValue, effectiveGender);
	}

	private String resolveProfileImageUrl(String profileImageValue, Gender gender) {
		if (!StringUtils.hasText(profileImageValue)) {
			return buildDefaultProfileImageUrl(resolveDefaultProfileImageFilename(gender));
		}

		String normalizedValue = profileImageValue.trim();
		String loweredValue = normalizedValue.toLowerCase(Locale.ROOT);

		if (DEFAULT_IMAGE_VALUE.equals(loweredValue)) {
			return buildDefaultProfileImageUrl(resolveDefaultProfileImageFilename(gender));
		}

		if (loweredValue.startsWith(DEFAULT_IMAGE_PREFIX)) {
			String animalName = loweredValue.substring(DEFAULT_IMAGE_PREFIX.length());
			return buildDefaultProfileImageUrl(resolveAnimalProfileImageFilename(animalName, gender));
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
		String encodedFilename = UriUtils.encodePathSegment(filename, StandardCharsets.UTF_8);
		return profileImageProperties.baseUrl() + encodedFilename;
	}

	private String resolveDefaultProfileImageFilename(Gender gender) {
		if (gender == Gender.FEMALE) {
			return DEFAULT_FEMALE_IMAGE_FILENAME;
		}
		return DEFAULT_MALE_IMAGE_FILENAME;
	}

	private String resolveAnimalProfileImageFilename(String animalName, Gender gender) {
		String normalizedAnimalName = animalName.toLowerCase(Locale.ROOT);
		String filename = findAnimalProfileImageByGender(normalizedAnimalName, gender);
		if (StringUtils.hasText(filename)) {
			return filename;
		}
		return resolveDefaultProfileImageFilename(gender);
	}

	private String findAnimalProfileImageByGender(String animalName, Gender gender) {
		if (gender == Gender.FEMALE) {
			if (FEMALE_ANIMAL_IMAGE_FILENAMES.containsKey(animalName)) {
				return FEMALE_ANIMAL_IMAGE_FILENAMES.get(animalName);
			}
			if (MALE_ANIMAL_IMAGE_FILENAMES.containsKey(animalName)) {
				return MALE_ANIMAL_IMAGE_FILENAMES.get(animalName);
			}
		}

		if (MALE_ANIMAL_IMAGE_FILENAMES.containsKey(animalName)) {
			return MALE_ANIMAL_IMAGE_FILENAMES.get(animalName);
		}
		if (FEMALE_ANIMAL_IMAGE_FILENAMES.containsKey(animalName)) {
			return FEMALE_ANIMAL_IMAGE_FILENAMES.get(animalName);
		}
		return NEUTRAL_ANIMAL_IMAGE_FILENAMES.get(animalName);
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
				.map(dto -> new ProfileTag(parseProfileTagItem(dto.tag())))
				.toList();
		}
		return newTags;
	}

	private static ProfileTagItem parseProfileTagItem(String tagValue) {
		try {
			return ProfileTagItem.fromCodeOrLabel(tagValue);
		} catch (IllegalArgumentException e) {
			throw new BusinessException(
				UserErrorCode.INVALID_PROFILE_TAG,
				Map.of("invalidTag", String.valueOf(tagValue)),
				UserErrorCode.INVALID_PROFILE_TAG.getMessage()
			);
		}
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
				.map(t -> new ProfileTagDto(t.getTag().getLabel()))
				.toList())
			.build();

	}
}
