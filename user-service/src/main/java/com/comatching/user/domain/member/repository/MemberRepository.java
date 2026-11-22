package com.comatching.user.domain.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.domain.enums.SocialType;
import com.comatching.user.domain.member.entity.Member;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {

	// 복구 판단에 쓰는 상태와 프로필(취미 포함)을 같은 SELECT의 스냅샷으로 읽는다.
	@Query("""
		select distinct m from Member m
		left join fetch m.profile p
		left join fetch p.hobbies
		where m.id = :memberId
		""")
	Optional<Member> findMatchingSnapshotById(@Param("memberId") Long memberId);

	Optional<Member> findBySocialTypeAndSocialId(SocialType socialType, String socialId);

	Optional<Member> findByEmail(String email);

	boolean existsByEmail(String email);

	long countByRoleAndStatus(MemberRole role, MemberStatus status);

	@Query(value = "SELECT m FROM Member m " +
		"JOIN FETCH m.profile p " +
		"WHERE m.status = :status " +
		"AND m.role = :role " +
		"AND (:keyword IS NULL " +
		"OR LOWER(m.email) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
		"OR LOWER(p.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
		"OR LOWER(m.realName) LIKE LOWER(CONCAT('%', :keyword, '%')))",
		countQuery = "SELECT COUNT(m) FROM Member m " +
			"JOIN m.profile p " +
			"WHERE m.status = :status " +
			"AND m.role = :role " +
			"AND (:keyword IS NULL " +
			"OR LOWER(m.email) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
			"OR LOWER(p.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
			"OR LOWER(m.realName) LIKE LOWER(CONCAT('%', :keyword, '%')))")
	Page<Member> searchMembersForAdmin(
		@Param("status") MemberStatus status,
		@Param("role") MemberRole role,
		@Param("keyword") String keyword,
		Pageable pageable
	);

	@Query("SELECT m FROM Member m " +
		"JOIN FETCH m.profile p " +
		"WHERE m.id = :memberId " +
		"AND m.status = :status " +
		"AND m.role = :role")
	Optional<Member> findAdminMemberById(
		@Param("memberId") Long memberId,
		@Param("status") MemberStatus status,
		@Param("role") MemberRole role
	);

	@Query("SELECT m FROM Member m " +
		"JOIN FETCH m.profile p " +
		"WHERE m.id IN :memberIds " +
		"AND m.status = :status " +
		"AND m.role = :role")
	List<Member> findAdminMembersByIds(
		@Param("memberIds") List<Long> memberIds,
		@Param("status") MemberStatus status,
		@Param("role") MemberRole role
	);
}
