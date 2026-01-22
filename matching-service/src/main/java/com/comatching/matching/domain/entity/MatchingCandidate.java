package com.comatching.matching.domain.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.matching.domain.vo.Mbti;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
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

	@Embedded
	@AttributeOverride(name = "value", column = @Column(name = "mbti"))
	private Mbti mbti;

	private String major;

	private boolean isMatchable;

	@Embedded
	@AttributeOverride(name = "value", column = @Column(name = "age"))
	private KoreanAge age;

	@Enumerated(EnumType.STRING)
	private ContactFrequency contactFrequency;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "candidate_hobby_categories", joinColumns = @JoinColumn(name = "member_id"))
	@Enumerated(EnumType.STRING)
	private List<HobbyCategory> hobbyCategories = new ArrayList<>();

	public void syncProfile(
		Long profileId, Gender gender, String mbti, String major, ContactFrequency contactFrequency,
		List<HobbyCategory> hobbyCategories, LocalDate birthDate, Boolean isMatchable) {
		if (profileId != null) {
			this.profileId = profileId;
		}
		if (gender != null) {
			this.gender = gender;
		}
		if (mbti != null) {
			this.mbti = new Mbti(mbti);
		}
		if (major != null) {
			this.major = major;
		}
		if (contactFrequency != null) {
			this.contactFrequency = contactFrequency;
		}
		if (birthDate != null) {
			this.age = KoreanAge.fromBirthDate(birthDate);
		}
		if (isMatchable != null) {
			this.isMatchable = isMatchable;
		}

		if (hobbyCategories != null) {
			this.hobbyCategories.clear();
			this.hobbyCategories.addAll(hobbyCategories);
		}
	}

	public static MatchingCandidate create(Long memberId, Long profileId, Gender gender, String mbti, String major,
		ContactFrequency contactFrequency, List<HobbyCategory> hobbyCategories, LocalDate birthDate, boolean isMatchable) {
		MatchingCandidate candidate = new MatchingCandidate();
		candidate.memberId = memberId;
		candidate.syncProfile(profileId, gender, mbti, major, contactFrequency, hobbyCategories, birthDate, isMatchable);
		return candidate;
	}

	public boolean hasHobbyCategory(HobbyCategory category) {
		return category != null && this.hobbyCategories.contains(category);
	}

	public boolean matchesContactFrequency(ContactFrequency frequency) {
		return frequency == null || this.contactFrequency == frequency;
	}
}
