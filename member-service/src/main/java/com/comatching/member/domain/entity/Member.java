package com.comatching.member.domain.entity;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.domain.enums.SocialType;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
	name = "members",
	indexes = {
		@Index(name = "idx_social_info", columnList = "socialType, socialId")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "member_id")
	private Long id;

	@Column(nullable = false, unique = true)
	private String email;

	private String password;

	@Enumerated(EnumType.STRING)
	private SocialType socialType;

	private String socialId;

	@Enumerated(EnumType.STRING)
	private MemberRole role;

	@Enumerated(EnumType.STRING)
	private MemberStatus status = MemberStatus.ACTIVE;

	@OneToOne(mappedBy = "member", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
	private Profile profile;

	@Builder
	public Member(String email, String password, SocialType socialType,
		String socialId, MemberRole role, MemberStatus status) {
		this.email = email;
		this.password = password;
		this.socialType = socialType;
		this.socialId = socialId;
		this.role = role;
		this.status = status;
	}

	public void upgradeRoleToUser() {
		this.role = MemberRole.ROLE_USER;
	}
}
