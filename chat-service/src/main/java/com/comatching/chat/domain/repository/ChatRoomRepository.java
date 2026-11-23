package com.comatching.chat.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import com.comatching.chat.domain.entity.ChatRoom;

@Repository
public interface ChatRoomRepository extends MongoRepository<ChatRoom, String>, ChatRoomRepositoryCustom {

	@Query("{ '$or': [{ 'initiatorUserId': ?0, 'targetUserId': ?1 }, { 'initiatorUserId': ?1, 'targetUserId': ?0 }] }")
	List<ChatRoom> findBetweenMembers(Long viewerId, Long partnerId);

	Optional<ChatRoom> findByMatchingId(Long matchingId);

	List<ChatRoom> findByMatchingIdIn(List<Long> matchingIds);

	@Query("{ '$or': [ " +
		"{ 'initiatorUserId': ?0 }, " +
		"{ 'targetUserId': ?0 } " +
		"] }")
	List<ChatRoom> findMyChatRooms(Long userId, Sort sort);
}
