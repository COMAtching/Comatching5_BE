package com.comatching.user.domain.member.repository;

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
}
