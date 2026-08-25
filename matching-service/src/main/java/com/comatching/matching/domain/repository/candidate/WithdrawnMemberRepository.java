package com.comatching.matching.domain.repository.candidate;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.comatching.matching.domain.entity.WithdrawnMember;

import jakarta.persistence.LockModeType;

@Repository
public interface WithdrawnMemberRepository extends JpaRepository<WithdrawnMember, Long> {

	/**
	 * upsert 쪽에서 쓰는 잠금 조회. 행이 없으면 InnoDB 갭 락이 걸려서, 같은
	 * memberId 의 탈퇴 트랜잭션이 tombstone 을 넣으려면 이 트랜잭션이 끝날 때까지
	 * 기다려야 한다. 이 순서 강제가 없으면 "upsert 가 tombstone 없음을 확인 →
	 * 탈퇴가 tombstone 삽입 + 후보 삭제 커밋 → upsert 가 후보 삽입 커밋" 교차로
	 * 탈퇴 회원이 부활하는 좁은 창이 남는다. 두 이벤트는 서로 다른 리스너
	 * 스레드에서 동시에 처리될 수 있으므로 실제로 존재하는 창이다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<WithdrawnMember> findWithLockByMemberId(Long memberId);

	/**
	 * TTL 이 지난 tombstone 일괄 삭제. 파생 쿼리(deleteBy...)는 행을 전부
	 * 로딩한 뒤 한 건씩 지우므로 벌크 JPQL 로 한 문장에 지운다. 잠금 조회
	 * 경로(upsert·탈퇴)와 경합하더라도 행 단위 X 락끼리의 대기일 뿐이고,
	 * cutoff 이전 행만 건드리므로 최근 탈퇴 회원의 가드에는 영향이 없다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("delete from WithdrawnMember w where w.withdrawnAt < :cutoff")
	int deleteAllByWithdrawnAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
