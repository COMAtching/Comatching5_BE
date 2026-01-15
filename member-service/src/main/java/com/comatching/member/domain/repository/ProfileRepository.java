package com.comatching.member.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.comatching.member.domain.entity.Profile;

@Repository
public interface ProfileRepository extends JpaRepository<Profile, Long> {

	Optional<Profile> findByMemberId(Long memberId);

	List<Profile> findAllByMemberIdIn(List<Long> memberIds);
}
