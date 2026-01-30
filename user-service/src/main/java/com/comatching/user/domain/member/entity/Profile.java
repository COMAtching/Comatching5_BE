package com.comatching.user.domain.member.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.ProfileTagCategory;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.exception.BusinessException;
import com.comatching.user.global.exception.UserErrorCode;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "profile")
public class Profile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "profile_id")
	private Long id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false, unique = true)
	private Member member;

	/**
	 * 매칭에 필요한 필수 프로필 목록
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Gender gender;

	@Column(nullable = false)
	private LocalDate birthDate;

	@Column(nullable = false)
	private String mbti;

	@Column(nullable = false)
	private String university;

	@Column(nullable = false)
	private String major;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ContactFrequency contactFrequency;

	@OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ProfileHobby> hobbies = new ArrayList<>();

	@Column(nullable = false)
	private boolean isMatchable = true;

	/**
	 * 매칭에는 필요하지 않은 선택 프로필 목록
	 * todo: 프로필 생성 시 입력하지 않으면 nickname, 프로필 사진을 기본 랜덤으로 설정하기
	 */

	private String nickname;

	private String profileImageUrl;

	private String intro;

	@Enumerated(EnumType.STRING)
	private SocialAccountType socialAccountType;

	private String socialAccountId;

	private String song;

	private int point = 0;

	@OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ProfileTag> tags = new ArrayList<>();

	@Builder
	public Profile(Member member, String nickname, Gender gender, LocalDate birthDate, String intro, String mbti,
		String profileImageUrl, SocialAccountType socialAccountType, String socialAccountId, String university,
		String major, ContactFrequency contactFrequency, String song, List<ProfileHobby> hobbies, List<ProfileTag> tags) {
		this.member = member;
		this.nickname = nickname;
		this.gender = gender;
		this.birthDate = birthDate;
		this.intro = intro;
		this.mbti = mbti;
		this.profileImageUrl = profileImageUrl;
		this.socialAccountType = socialAccountType;
		this.socialAccountId = socialAccountId;
		this.university = university;
		this.major = major;
		this.contactFrequency = contactFrequency;
		this.song = song;

		if (hobbies != null)
			addHobbies(hobbies);
		if (tags != null)
			addTags(tags);
	}

	public void clearProfileData() {
		this.nickname = "탈퇴한 사용자";
		this.intro = null;
		this.profileImageUrl = null;
		this.birthDate = null;
		this.socialAccountType = null;
		this.socialAccountId = null;
		this.major = "(알 수 없음)";
		this.song = null;
		this.point = 0;
	}

	public void update(
		String nickname, String intro, String mbti,
		String profileImageUrl, Gender gender, LocalDate birthDate,
		SocialAccountType socialAccountType, String socialAccountId,
		String university, String major, ContactFrequency contactFrequency, String song,
		List<ProfileHobby> hobbies, List<ProfileTag> tags, Boolean isMatchable) {

		if (nickname != null)
			this.nickname = nickname;
		if (intro != null)
			this.intro = intro;
		if (mbti != null)
			this.mbti = mbti;
		if (profileImageUrl != null)
			this.profileImageUrl = profileImageUrl;
		if (gender != null)
			this.gender = gender;
		if (birthDate != null)
			this.birthDate = birthDate;
		if (university != null)
			this.university = university;
		if (major != null)
			this.major = major;
		if (contactFrequency != null)
			this.contactFrequency = contactFrequency;
		if (isMatchable != null)
			this.isMatchable = isMatchable;
		if (song != null)
			this.song = song;

		updateSocialInfo(socialAccountType, socialAccountId);

		if (hobbies != null)
			addHobbies(hobbies);
		addTags(tags);
	}

	public void addHobbies(List<ProfileHobby> newHobbies) {
		if (newHobbies == null || newHobbies.isEmpty() || newHobbies.size() > 10 || newHobbies.size() < 1) {
			throw new BusinessException(UserErrorCode.INVALID_HOBBY_COUNT);
		}

		this.hobbies.clear();
		for (ProfileHobby hobby : newHobbies) {
			this.hobbies.add(hobby);
			hobby.assignProfile(this);
		}
	}

	public List<HobbyCategory> getHobbyCategories() {
		return this.hobbies.stream()
			.map(ProfileHobby::getCategory)
			.toList();
	}

	public void addTags(List<ProfileTag> newTags) {
		if (newTags != null) {
			Map<ProfileTagCategory, Long> countByCategory = newTags.stream()
				.collect(Collectors.groupingBy(
					t -> t.getTag().getGroup().getCategory(),
					Collectors.counting()
				));
			countByCategory.forEach((cat, count) -> {
				if (count > 3) {
					throw new BusinessException(UserErrorCode.TAG_LIMIT_PER_CATEGORY_EXCEEDED);
				}
			});
		}
		this.tags.clear();
		if (newTags != null) {
			for (ProfileTag tag : newTags) {
				this.tags.add(tag);
				tag.assignProfile(this);
			}
		}
	}

	private void updateSocialInfo(SocialAccountType type, String id) {
		if (type == null && id == null) {
			return;
		}

		if (type == null || id == null) {
			throw new BusinessException(UserErrorCode.INVALID_SOCIAL_INFO);
		}

		this.socialAccountType = type;
		this.socialAccountId = id;
	}

	public void addPoint(int point) {
		this.point += point;
	}

	public void minusPoint(int point) {
		this.point -= point;
	}
}
