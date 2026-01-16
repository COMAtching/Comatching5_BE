package com.comatching.chat.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import com.comatching.chat.domain.entity.ChatMessage;

@Repository
public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {

	List<ChatMessage> findByRoomIdOrderByCreatedAtDesc(String roomId, Pageable pageable);

	@Query(value = "{ 'roomId': ?0, 'createdAt': { $gt: ?1 }, 'senderId': { $ne: ?2 } }", count = true)
	long countUnreadMessages(String roomId, LocalDateTime lastReadAt, Long myMemberId);
}
