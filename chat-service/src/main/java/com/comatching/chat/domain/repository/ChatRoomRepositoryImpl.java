package com.comatching.chat.domain.repository;

import java.time.LocalDateTime;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.enums.ChatRoomStatus;
import com.mongodb.client.result.UpdateResult;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ChatRoomRepositoryImpl implements ChatRoomRepositoryCustom {

	private final MongoTemplate mongoTemplate;

	@Override
	public boolean touchLastReadAt(String roomId, Long memberId, LocalDateTime readAt) {
		UpdateResult initiatorUpdated = mongoTemplate.updateFirst(
			Query.query(Criteria.where("_id").is(roomId).and("initiatorUserId").is(memberId)),
			new Update()
				.max("initiatorLastReadAt", readAt)
				.max("updatedAt", readAt),
			ChatRoom.class
		);
		if (initiatorUpdated.getMatchedCount() > 0) {
			return true;
		}

		UpdateResult targetUpdated = mongoTemplate.updateFirst(
			Query.query(Criteria.where("_id").is(roomId).and("targetUserId").is(memberId)),
			new Update()
				.max("targetLastReadAt", readAt)
				.max("updatedAt", readAt),
			ChatRoom.class
		);
		return targetUpdated.getMatchedCount() > 0;
	}

	@Override
	public boolean updateLastMessageIfLatest(String roomId, String previewContent, LocalDateTime sentAt) {
		Criteria latestMessageCriteria = new Criteria().orOperator(
			Criteria.where("lastMessageInfo").exists(false),
			Criteria.where("lastMessageInfo").is(null),
			Criteria.where("lastMessageInfo.sentAt").lte(sentAt)
		);

		Query query = Query.query(new Criteria().andOperator(
			Criteria.where("_id").is(roomId),
			latestMessageCriteria
		));
		Update update = new Update()
			.set("lastMessageInfo.content", previewContent)
			.set("lastMessageInfo.sentAt", sentAt)
			.set("status", ChatRoomStatus.ACTIVE)
			.max("updatedAt", sentAt);

		UpdateResult result = mongoTemplate.updateFirst(query, update, ChatRoom.class);
		return result.getMatchedCount() > 0;
	}
}
