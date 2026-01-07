package com.comatching.member.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.SocialAccountType;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "profile")
public class Profile {

	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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

	@Builder
	public Profile(Member member, String nickname, Gender gender, LocalDate birthDate, String intro, String mbti,
		String profileImageUrl, SocialAccountType socialAccountType, String socialAccountId) {
		this.member = member;
		this.nickname = nickname;
		this.gender = gender;
		this.birthDate = birthDate;
		this.intro = intro;
		this.mbti = mbti;
		this.profileImageUrl = profileImageUrl;
		this.socialAccountType = socialAccountType;
		this.socialAccountId = socialAccountId;
	}

	public void updateSocialAccountInfo(SocialAccountType socialAccountType, String socialAccountId) {
		this.socialAccountType = socialAccountType;
		this.socialAccountId = socialAccountId;
	}
}
