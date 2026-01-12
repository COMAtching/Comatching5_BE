package com.comatching.matching.domain.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "matching_history", indexes = {
	@Index(name = "idx_history_member", columnList = "memberId"),
	@Index(name = "idx_history_partner", columnList = "partnerId")
},
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_history_member_partner",
			columnNames = {"memberId", "partnerId"}
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

	@CreatedDate
	private LocalDateTime matchedAt = LocalDateTime.now();

	@Builder
	public MatchingHistory(Long memberId, Long partnerId) {
		this.memberId = memberId;
		this.partnerId = partnerId;
	}
}
