package com.comatching.chat.domain.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.enums.ChatRoomStatus;
import com.mongodb.client.result.UpdateResult;

@ExtendWith(MockitoExtension.class)
class ChatRoomRepositoryImplTest {

	private static final String ROOM_ID = "room-1";
	private static final Long INITIATOR_ID = 1L;
	private static final Long TARGET_ID = 2L;

	@Mock
	private MongoTemplate mongoTemplate;

	@InjectMocks
	private ChatRoomRepositoryImpl repository;

	@Test
	@DisplayName("initiator readAt은 initiatorLastReadAt만 max update 한다")
	void touchLastReadAt_updatesInitiatorFieldOnly() {
		// given
		LocalDateTime readAt = LocalDateTime.of(2026, 5, 16, 10, 0);
		given(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ChatRoom.class)))
			.willReturn(UpdateResult.acknowledged(1, 1L, null));

		// when
		boolean result = repository.touchLastReadAt(ROOM_ID, INITIATOR_ID, readAt);

		// then
		assertThat(result).isTrue();
		ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
		ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
		then(mongoTemplate).should().updateFirst(queryCaptor.capture(), updateCaptor.capture(), eq(ChatRoom.class));

		Document query = queryCaptor.getValue().getQueryObject();
		Document update = updateCaptor.getValue().getUpdateObject();
		assertThat(query).containsEntry("_id", ROOM_ID);
		assertThat(query).containsEntry("initiatorUserId", INITIATOR_ID);
		assertThat(update.get("$max", Document.class)).containsEntry("initiatorLastReadAt", readAt);
		assertThat(update.get("$max", Document.class)).containsEntry("updatedAt", readAt);
		assertThat(update.toString()).doesNotContain("targetLastReadAt");
	}

	@Test
	@DisplayName("initiator가 아니면 targetLastReadAt을 max update 한다")
	void touchLastReadAt_fallsBackToTargetField() {
		// given
		LocalDateTime readAt = LocalDateTime.of(2026, 5, 16, 10, 1);
		given(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ChatRoom.class)))
			.willReturn(UpdateResult.acknowledged(0, 0L, null))
			.willReturn(UpdateResult.acknowledged(1, 1L, null));

		// when
		boolean result = repository.touchLastReadAt(ROOM_ID, TARGET_ID, readAt);

		// then
		assertThat(result).isTrue();
		ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
		ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
		then(mongoTemplate).should(times(2))
			.updateFirst(queryCaptor.capture(), updateCaptor.capture(), eq(ChatRoom.class));

		Document targetQuery = queryCaptor.getAllValues().get(1).getQueryObject();
		Document targetUpdate = updateCaptor.getAllValues().get(1).getUpdateObject();
		assertThat(targetQuery).containsEntry("_id", ROOM_ID);
		assertThat(targetQuery).containsEntry("targetUserId", TARGET_ID);
		assertThat(targetUpdate.get("$max", Document.class)).containsEntry("targetLastReadAt", readAt);
		assertThat(targetUpdate.get("$max", Document.class)).containsEntry("updatedAt", readAt);
	}

	@Test
	@DisplayName("lastMessage는 기존 sentAt이 없거나 더 오래된 경우에만 갱신한다")
	void updateLastMessageIfLatest_usesConditionalAtomicUpdate() {
		// given
		LocalDateTime sentAt = LocalDateTime.of(2026, 5, 16, 10, 2);
		given(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ChatRoom.class)))
			.willReturn(UpdateResult.acknowledged(1, 1L, null));

		// when
		boolean result = repository.updateLastMessageIfLatest(ROOM_ID, "hello", sentAt);

		// then
		assertThat(result).isTrue();
		ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
		ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
		then(mongoTemplate).should().updateFirst(queryCaptor.capture(), updateCaptor.capture(), eq(ChatRoom.class));

		Document query = queryCaptor.getValue().getQueryObject();
		Document update = updateCaptor.getValue().getUpdateObject();
		assertThat(query.toString()).contains("_id");
		assertThat(query.toString()).contains("lastMessageInfo.sentAt");
		assertThat(query.toString()).contains("$lte");
		assertThat(update.get("$set", Document.class)).containsEntry("lastMessageInfo.content", "hello");
		assertThat(update.get("$set", Document.class)).containsEntry("lastMessageInfo.sentAt", sentAt);
		assertThat(update.get("$set", Document.class)).containsEntry("status", ChatRoomStatus.ACTIVE);
		assertThat(update.get("$max", Document.class)).containsEntry("updatedAt", sentAt);
	}
}
