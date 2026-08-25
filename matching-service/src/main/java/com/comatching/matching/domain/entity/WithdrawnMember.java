package com.comatching.matching.domain.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 탈퇴 tombstone.
 *
 * member-withdraw 와 profile-updates 는 서로 다른 토픽이라 메시지 키를 걸어도
 * 둘 사이의 순서는 Kafka 가 보장해 주지 않는다(키의 순서 보장 범위는 한 토픽의
 * 한 파티션). 탈퇴가 먼저 처리되고 뒤늦게 갱신 이벤트가 도착하면 upsert 가
 * 지워진 후보를 되살린다. 삭제를 "레코드 없음"이 아니라 이 레코드의 존재로
 * 남겨서, 늦게 온 갱신이 탈퇴를 뒤집지 못하게 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "withdrawn_member")
public class WithdrawnMember {

	@Id
	private Long memberId;

	private LocalDateTime withdrawnAt;

	public static WithdrawnMember of(Long memberId, LocalDateTime withdrawnAt) {
		WithdrawnMember withdrawnMember = new WithdrawnMember();
		withdrawnMember.memberId = memberId;
		withdrawnMember.withdrawnAt = withdrawnAt;
		return withdrawnMember;
	}
}
