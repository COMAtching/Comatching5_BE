package com.comatching.member.domain.entity;

import com.comatching.common.domain.enums.HobbyCategory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "profile_hobby")
public class ProfileHobby {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "profile_id")
	private Profile profile;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private HobbyCategory category;

	@Column(nullable = false, length = 50)
	private String name;

	public ProfileHobby(HobbyCategory category, String name) {
		this.category = category;
		this.name = name;
	}

	public void assignProfile(Profile profile) {
		this.profile = profile;
	}
}
