package com.comatching.user.domain.member.entity;

import com.comatching.common.domain.enums.ProfileTagItem;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "profile_tag")
public class ProfileTag {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "profile_id")
	private Profile profile;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ProfileTagItem tag;

	public ProfileTag(ProfileTagItem tag) {
		this.tag = tag;
	}

	public void assignProfile(Profile profile) {
		this.profile = profile;
	}
}
