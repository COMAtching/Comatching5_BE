package com.comatching.user.domain.member.repository;

import java.util.Optional;
import java.util.List;

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

	@Query("SELECT m FROM Member m " +
		"JOIN FETCH m.profile p " +
		"WHERE m.status = :status " +
		"AND m.role = :role " +
		"AND (:keyword IS NULL " +
		"OR LOWER(m.email) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
		"OR LOWER(p.nickname) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
		"ORDER BY m.id DESC")
	List<Member> searchMembersForAdmin(
		@Param("status") MemberStatus status,
		@Param("role") MemberRole role,
		@Param("keyword") String keyword
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
