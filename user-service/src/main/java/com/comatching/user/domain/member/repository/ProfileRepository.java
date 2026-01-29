package com.comatching.user.domain.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.comatching.user.domain.member.entity.Profile;

@Repository
public interface ProfileRepository extends JpaRepository<Profile, Long> {

	Optional<Profile> findByMemberId(Long memberId);

	@Query("SELECT DISTINCT p FROM Profile p " +
		"JOIN FETCH p.member m " +
		"LEFT JOIN FETCH p.intros " +
		"WHERE m.id IN :memberIds")
	List<Profile> findAllByMemberIdIn(@Param("memberIds") List<Long> memberIds);
}
