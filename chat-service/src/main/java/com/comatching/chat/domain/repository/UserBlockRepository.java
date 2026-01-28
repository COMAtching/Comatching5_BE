package com.comatching.chat.domain.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.comatching.chat.domain.entity.UserBlock;

@Repository
public interface UserBlockRepository extends MongoRepository<UserBlock, String> {

	boolean existsByBlockerUserIdAndBlockedUserId(Long blockerUserId, Long blockedUserId);

	List<UserBlock> findByBlockerUserId(Long blockerUserId);

	void deleteByBlockerUserIdAndBlockedUserId(Long blockerUserId, Long blockedUserId);
}
