package com.comatching.user.domain.member.dto;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileTagDto;

public class ProfileUpdateRequest {

	private String nickname;
	private String intro;
	private String mbti;
	private String profileImageUrl;
	private String profileImageKey;
	private Gender gender;
	private LocalDate birthDate;
	private SocialAccountType socialType;
	private String socialAccountId;
	private boolean socialTypeProvided;
	private boolean socialAccountIdProvided;
	private String university;
	private String major;
	private ContactFrequency contactFrequency;
	private String song;
	private List<HobbyDto> hobbies;
	private List<ProfileTagDto> tags;
	private Boolean isMatchable;

	public ProfileUpdateRequest() {
	}

	public ProfileUpdateRequest(
		String nickname,
		String intro,
		String mbti,
		String profileImageUrl,
		String profileImageKey,
		Gender gender,
		LocalDate birthDate,
		SocialAccountType socialType,
		String socialAccountId,
		String university,
		String major,
		ContactFrequency contactFrequency,
		String song,
		List<HobbyDto> hobbies,
		List<ProfileTagDto> tags,
		Boolean isMatchable
	) {
		this.nickname = nickname;
		this.intro = intro;
		this.mbti = mbti;
		this.profileImageUrl = profileImageUrl;
		this.profileImageKey = profileImageKey;
		this.gender = gender;
		this.birthDate = birthDate;
		this.socialType = socialType;
		this.socialAccountId = socialAccountId;
		this.socialTypeProvided = socialType != null;
		this.socialAccountIdProvided = socialAccountId != null;
		this.university = university;
		this.major = major;
		this.contactFrequency = contactFrequency;
		this.song = song;
		this.hobbies = hobbies;
		this.tags = tags;
		this.isMatchable = isMatchable;
	}

	public ProfileUpdateRequest(
		String nickname,
		String intro,
		String mbti,
		String profileImageUrl,
		Gender gender,
		LocalDate birthDate,
		SocialAccountType socialType,
		String socialAccountId,
		String university,
		String major,
		ContactFrequency contactFrequency,
		String song,
		List<HobbyDto> hobbies,
		List<ProfileTagDto> tags,
		Boolean isMatchable
	) {
		this(
			nickname,
			intro,
			mbti,
			profileImageUrl,
			null,
			gender,
			birthDate,
			socialType,
			socialAccountId,
			university,
			major,
			contactFrequency,
			song,
			hobbies,
			tags,
			isMatchable
		);
	}

	public String nickname() {
		return nickname;
	}

	public String intro() {
		return intro;
	}

	public String mbti() {
		return mbti;
	}

	public String profileImageUrl() {
		return profileImageUrl;
	}

	public String profileImageKey() {
		return profileImageKey;
	}

	public Gender gender() {
		return gender;
	}

	public LocalDate birthDate() {
		return birthDate;
	}

	public SocialAccountType socialType() {
		return socialType;
	}

	public String socialAccountId() {
		return socialAccountId;
	}

	@JsonIgnore
	public boolean socialInfoProvided() {
		return socialTypeProvided || socialAccountIdProvided;
	}

	public String university() {
		return university;
	}

	public String major() {
		return major;
	}

	public ContactFrequency contactFrequency() {
		return contactFrequency;
	}

	public String song() {
		return song;
	}

	public List<HobbyDto> hobbies() {
		return hobbies;
	}

	public List<ProfileTagDto> tags() {
		return tags;
	}

	public Boolean isMatchable() {
		return isMatchable;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public void setIntro(String intro) {
		this.intro = intro;
	}

	public void setMbti(String mbti) {
		this.mbti = mbti;
	}

	public void setProfileImageUrl(String profileImageUrl) {
		this.profileImageUrl = profileImageUrl;
	}

	public void setProfileImageKey(String profileImageKey) {
		this.profileImageKey = profileImageKey;
	}

	public void setGender(Gender gender) {
		this.gender = gender;
	}

	public void setBirthDate(LocalDate birthDate) {
		this.birthDate = birthDate;
	}

	public void setSocialType(SocialAccountType socialType) {
		this.socialType = socialType;
		this.socialTypeProvided = true;
	}

	public void setSocialAccountId(String socialAccountId) {
		this.socialAccountId = socialAccountId;
		this.socialAccountIdProvided = true;
	}

	public void setUniversity(String university) {
		this.university = university;
	}

	public void setMajor(String major) {
		this.major = major;
	}

	public void setContactFrequency(ContactFrequency contactFrequency) {
		this.contactFrequency = contactFrequency;
	}

	public void setSong(String song) {
		this.song = song;
	}

	public void setHobbies(List<HobbyDto> hobbies) {
		this.hobbies = hobbies;
	}

	public void setTags(List<ProfileTagDto> tags) {
		this.tags = tags;
	}

	public void setIsMatchable(Boolean isMatchable) {
		this.isMatchable = isMatchable;
	}
}
