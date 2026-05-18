package com.comatching.matching.domain.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "matching_history",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_history_member_partner",
			columnNames = {"memberId", "partnerId"}
		),
		@UniqueConstraint(
			name = "uk_history_pair_key",
			columnNames = {"pairKey"}
		)
	}
)
public class MatchingHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long memberId;

	@Column(nullable = false)
	private Long partnerId;

	@Column(nullable = false, length = 50)
	private String pairKey;

	@Column(nullable = false)
	private boolean favorite;

	@Embedded
	private MatchingCondition condition;

	@CreatedDate
	private LocalDateTime matchedAt = LocalDateTime.now();

	@Builder
	public MatchingHistory(Long memberId, Long partnerId, MatchingCondition condition) {
		this.memberId = memberId;
		this.partnerId = partnerId;
		this.pairKey = pairKeyOf(memberId, partnerId);
		this.condition = condition;
		this.favorite = false;
	}

	public void updateFavorite(boolean favorite) {
		this.favorite = favorite;
	}

	@PrePersist
	@PreUpdate
	private void syncPairKey() {
		this.pairKey = pairKeyOf(memberId, partnerId);
	}

	public static String pairKeyOf(Long memberId, Long partnerId) {
		if (memberId == null || partnerId == null) {
			throw new IllegalArgumentException("memberId and partnerId are required to create matching pair key.");
		}
		long first = Math.min(memberId, partnerId);
		long second = Math.max(memberId, partnerId);
		return first + ":" + second;
	}
}
