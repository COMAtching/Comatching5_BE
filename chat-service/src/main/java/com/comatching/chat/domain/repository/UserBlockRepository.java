package com.comatching.chat.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.comatching.chat.domain.entity.UserBlock;

@Repository
public interface UserBlockRepository extends MongoRepository<UserBlock, String> {

	Optional<UserBlock> findByBlockerUserIdAndBlockedUserId(Long blockerUserId, Long blockedUserId);

	List<UserBlock> findByBlockerUserId(Long blockerUserId);

	@Query("{'blockerUserId': ?0, 'blockedUserId': ?1, 'unblockedAt': {$ne: null}}")
	@Update("{$unset: {'unblockedAt': ''}, $currentDate: {'createdAt': true}}")
	long reactivate(Long blockerUserId, Long blockedUserId);

	@Query("{'blockerUserId': ?0, 'blockedUserId': ?1, 'unblockedAt': null}")
	@Update("{$currentDate: {'unblockedAt': true}}")
	long release(Long blockerUserId, Long blockedUserId);
}
