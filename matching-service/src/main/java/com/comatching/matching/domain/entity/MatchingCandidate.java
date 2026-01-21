package com.comatching.matching.domain.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.comatching.common.domain.enums.ContactFrequency;
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

	@Enumerated(EnumType.STRING)
	private ContactFrequency contactFrequency;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "candidate_hobby_categories", joinColumns = @JoinColumn(name = "member_id"))
	@Enumerated(EnumType.STRING)
	private List<Hobby.Category> hobbyCategories = new ArrayList<>();

	public void syncProfile(
		Long profileId, Gender gender, String mbti, String major, ContactFrequency contactFrequency,
		List<Hobby> hobbies, LocalDate birthDate, Boolean isMatchable) {
		if (profileId != null) {
			this.profileId = profileId;
		}
		if (gender != null) {
			this.gender = gender;
		}
		if (mbti != null) {
			this.mbti = mbti;
		}
		if (major != null) {
			this.major = major;
		}
		if (contactFrequency != null) {
			this.contactFrequency = contactFrequency;
		}
		if (birthDate != null) {
			this.age = birthDate.until(LocalDate.now()).getYears() + 1;
		}
		if (isMatchable != null) {
			this.isMatchable = isMatchable;
		}

		if (hobbies != null) {
			this.hobbyCategories.clear();
			this.hobbyCategories.addAll(
				hobbies.stream()
					.map(Hobby::getCategory)
					.toList()
			);
		}
	}

	public static MatchingCandidate create(Long memberId, Long profileId, Gender gender, String mbti, String major,
		ContactFrequency contactFrequency, List<Hobby> hobbies, LocalDate birthDate, boolean isMatchable) {
		MatchingCandidate candidate = new MatchingCandidate();
		candidate.memberId = memberId;
		candidate.syncProfile(profileId, gender, mbti, major, contactFrequency, hobbies, birthDate, isMatchable);
		return candidate;
	}
}
