package com.comatching.matching.domain.repository.candidate;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.comatching.matching.domain.entity.MatchingCandidate;

import jakarta.persistence.LockModeType;

@Repository
public interface MatchingCandidateRepository extends JpaRepository<MatchingCandidate, Long>, MatchingCandidateRepositoryCustom {

	boolean existsByMemberId(Long memberId);

	/**
	 * 탈퇴 처리의 후보 삭제용. 잠금 조회(current read)는 REPEATABLE READ 스냅샷과
	 * 무관하게 최신 커밋을 읽는다. 일반 조회로 지우면 이 트랜잭션이 시작된 뒤에
	 * 커밋된 후보(동시 upsert 가 만든 행)를 보지 못해 삭제를 건너뛴다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<MatchingCandidate> findWithLockByMemberId(Long memberId);
}
