package com.comatching.user.domain.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.comatching.common.domain.enums.SocialType;
import com.comatching.user.domain.member.entity.Member;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {

	Optional<Member> findBySocialTypeAndSocialId(SocialType socialType, String socialId);

	Optional<Member> findByEmail(String email);

	boolean existsByEmail(String email);
}
