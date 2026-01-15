package com.comatching.member.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.exception.BusinessException;
import com.comatching.member.global.exception.MemberErrorCode;

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

	@Column(nullable = false)
	private String nickname;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Gender gender;

	private LocalDate birthDate;

	private String intro;

	@Column(nullable = false)
	private String mbti;

	private String profileImageUrl;

	@Enumerated(EnumType.STRING)
	private SocialAccountType socialAccountType;

	private String socialAccountId;

	@Column(nullable = false)
	private String university;

	@Column(nullable = false)
	private String major;

	@Column(nullable = false)
	private boolean isMatchable = true;

	private int point = 0;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(
		name = "profile_hobbies",
		joinColumns = @JoinColumn(name = "profile_id")
	)
	@Enumerated(EnumType.STRING)
	@Column(name = "hobby")
	private List<Hobby> hobbies = new ArrayList<>();

	@OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ProfileIntro> intros = new ArrayList<>();

	@Builder
	public Profile(Member member, String nickname, Gender gender, LocalDate birthDate, String intro, String mbti,
		String profileImageUrl, SocialAccountType socialAccountType, String socialAccountId, String university,
		String major, List<Hobby> hobbies, List<ProfileIntro> intros) {
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

		if (hobbies != null)
			addHobbies(hobbies);
		if (intros != null)
			addIntros(intros);
	}

	public void clearProfileData() {
		this.nickname = "탈퇴한 사용자";
		this.intro = null;
		this.profileImageUrl = null;
		this.birthDate = null;
		this.socialAccountType = null;
		this.socialAccountId = null;
		this.major = "(알 수 없음)";
		this.point = 0;
	}

	public void update(
		String nickname, String intro, String mbti,
		String profileImageUrl, Gender gender, LocalDate birthDate,
		SocialAccountType socialAccountType, String socialAccountId,
		String university, String major,
		List<Hobby> hobbies, List<ProfileIntro> intros, Boolean isMatchable) {

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
		if (isMatchable != null)
			this.isMatchable = isMatchable;

		updateSocialInfo(socialAccountType, socialAccountId);

		if (hobbies != null)
			addHobbies(hobbies);
		addIntros(intros);


	}

	public void addHobbies(List<Hobby> newHobbies) {
		if (newHobbies == null || newHobbies.isEmpty() || newHobbies.size() > 10 || newHobbies.size() < 1) {
			throw new BusinessException(MemberErrorCode.INVALID_HOBBY_COUNT);
		}

		this.hobbies.clear();
		this.hobbies.addAll(newHobbies);
	}

	public void addIntros(List<ProfileIntro> newIntros) {
		if (newIntros != null && newIntros.size() > 3) {
			throw new BusinessException(MemberErrorCode.INTRO_LIMIT_EXCEEDED);
		}
		this.intros.clear();
		if (newIntros != null) {
			for (ProfileIntro intro : newIntros) {
				this.intros.add(intro);
				intro.assignProfile(this);
			}
		}
	}

	private void updateSocialInfo(SocialAccountType type, String id) {
		if (type == null && id == null) {
			return;
		}

		if (type == null || id == null) {
			throw new BusinessException(MemberErrorCode.INVALID_SOCIAL_INFO);
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
