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

	Optional<ChatRoom> findByMatchingId(Long matchingId);

	List<ChatRoom> findByMatchingIdIn(List<Long> matchingIds);

	@Query("{ '$or': [ " +
		"{ 'initiatorUserId': ?0 }, " +
		"{ 'targetUserId': ?0 } " +
		"] }")
	List<ChatRoom> findMyChatRooms(Long userId, Sort sort);
}
