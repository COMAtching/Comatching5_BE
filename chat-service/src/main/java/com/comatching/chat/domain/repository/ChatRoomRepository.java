package com.comatching.chat.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import com.comatching.chat.domain.entity.ChatRoom;

@Repository
public interface ChatRoomRepository extends MongoRepository<ChatRoom, String> {

	Optional<ChatRoom> findByMatchingId(Long matchingId);

	@Query("{ '$or': [ " +
		"{ 'initiatorUserId': ?0 }, " +
		"{ 'targetUserId': ?0, 'status': 'ACTIVE' } " +
		"] }")
	List<ChatRoom> findMyChatRooms(Long userId, Sort sort);
}
