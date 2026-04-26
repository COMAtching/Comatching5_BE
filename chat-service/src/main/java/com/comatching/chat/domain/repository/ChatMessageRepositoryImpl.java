package com.comatching.chat.domain.repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;

import com.comatching.chat.domain.entity.ChatMessage;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ChatMessageRepositoryImpl implements ChatMessageRepositoryCustom {

	private final MongoTemplate mongoTemplate;

	@Override
	public Map<String, Long> countUnreadMessagesByRoom(List<UnreadCountCondition> conditions, Long myMemberId) {
		if (conditions.isEmpty()) {
			return Map.of();
		}

		Criteria[] unreadRoomCriteria = conditions.stream()
			.map(this::toUnreadRoomCriteria)
			.toArray(Criteria[]::new);

		Criteria matchCriteria = new Criteria().andOperator(
			Criteria.where("senderId").ne(myMemberId),
			new Criteria().orOperator(unreadRoomCriteria)
		);

		Aggregation aggregation = Aggregation.newAggregation(
			Aggregation.match(matchCriteria),
			Aggregation.group("roomId").count().as("count")
		);

		return mongoTemplate.aggregate(aggregation, ChatMessage.class, Document.class)
			.getMappedResults()
			.stream()
			.collect(Collectors.toMap(
				result -> result.getString("_id"),
				result -> result.get("count", Number.class).longValue()
			));
	}

	private Criteria toUnreadRoomCriteria(UnreadCountCondition condition) {
		Criteria criteria = Criteria.where("roomId").is(condition.roomId());
		if (condition.lastReadAt() != null) {
			criteria = criteria.and("createdAt").gt(condition.lastReadAt());
		}
		return criteria;
	}
}
