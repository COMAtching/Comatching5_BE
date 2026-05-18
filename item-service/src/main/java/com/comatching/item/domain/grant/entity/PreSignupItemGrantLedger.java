package com.comatching.item.domain.grant.entity;

import java.time.LocalDateTime;

import com.comatching.common.domain.enums.ItemType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "pre_signup_item_grant_ledger",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_pre_signup_grant_campaign_member_item",
			columnNames = {"campaign_code", "member_id", "item_type"}
		)
	}
)
public class PreSignupItemGrantLedger {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "campaign_code", nullable = false, length = 64)
	private String campaignCode;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "item_type", nullable = false, length = 50)
	private ItemType itemType;

	@Column(name = "granted_at", nullable = false)
	private LocalDateTime grantedAt;

	@Builder
	public PreSignupItemGrantLedger(String campaignCode, Long memberId, ItemType itemType) {
		this.campaignCode = campaignCode;
		this.memberId = memberId;
		this.itemType = itemType;
		this.grantedAt = LocalDateTime.now();
	}
}
