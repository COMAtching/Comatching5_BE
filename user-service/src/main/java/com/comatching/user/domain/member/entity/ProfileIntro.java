package com.comatching.user.domain.member.entity;

import com.comatching.common.domain.enums.IntroQuestion;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "profile_intro")
public class ProfileIntro {

	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "profile_id")
	private Profile profile;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private IntroQuestion question;

	@Column(nullable = false)
	private String answer;

	public ProfileIntro(IntroQuestion question, String answer) {
		this.question = question;
		this.answer = answer;
	}

	public void assignProfile(Profile profile) {
		this.profile = profile;
	}
}
