package com.comatching.matching.domain.entity;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "matching_candidate",
	indexes = {
		@Index(name = "idx_candidate_basic", columnList = "gender, is_matchable"),
		@Index(name = "idx_candidate_major", columnList = "major")
	}
)
public class MatchingCandidate {

	@Id
	private Long memberId;

	private Long profileId;

	@Enumerated(EnumType.STRING)
	private Gender gender;

	private String mbti;

	private String major;

	private boolean isMatchable;

	private int age;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "candidate_hobby_categories", joinColumns = @JoinColumn(name = "member_id"))
	@Enumerated(EnumType.STRING)
	private Set<Hobby.Category> hobbyCategories = new HashSet<>();

	public void syncProfile(Long profileId, Gender gender, String mbti, String major, Set<Hobby> hobbies, LocalDate birthDate, boolean isMatchable) {
		this.profileId = profileId;
		this.gender = gender;
		this.mbti = mbti;
		this.major = major;
		this.age = birthDate.until(LocalDate.now()).getYears() + 1;
		this.isMatchable = isMatchable;
		this.hobbyCategories.clear();
		if (hobbies != null) {
			this.hobbyCategories = hobbies.stream()
				.map(Hobby::getCategory)
				.collect(Collectors.toSet());
		}
	}

	public static MatchingCandidate create(Long memberId, Long profileId, Gender gender, String mbti, String major, Set<Hobby> hobbies, LocalDate birthDate, boolean isMatchable) {
		MatchingCandidate candidate = new MatchingCandidate();
		candidate.memberId = memberId;
		candidate.syncProfile(profileId, gender, mbti, major, hobbies, birthDate, isMatchable);
		return candidate;
	}
}
