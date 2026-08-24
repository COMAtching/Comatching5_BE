package com.comatching.matching.domain.repository.candidate;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
