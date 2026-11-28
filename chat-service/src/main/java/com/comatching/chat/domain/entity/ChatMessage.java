package com.comatching.chat.domain.entity;

import com.comatching.chat.domain.enums.MessageType;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "chat_messages")
@CompoundIndexes({
        @CompoundIndex(name = "room_created_idx", def = "{'roomId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "room_message_order", def = "{'roomId': 1, 'createdAt': -1, '_id': -1}"),
        @CompoundIndex(
                name = "chat_push_due_idx",
                def = "{'pushOutbox.availableAt': 1}",
                partialFilter = "{'pushOutbox.availableAt': {$exists: true}}"
        )
})
public class ChatMessage {

    @Id
    private String id;

    private String roomId;

    private Long senderId;

    private String content;

    private MessageType type;

    private PushOutbox pushOutbox;

    private LocalDateTime createdAt;

    @Builder
    public ChatMessage(
            String roomId,
            Long senderId,
            String content,
            MessageType type,
            PushOutbox pushOutbox,
            LocalDateTime createdAt
    ) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.content = content;
        this.type = type;
        this.pushOutbox = pushOutbox;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    public record PushOutbox(
            Long targetUserId,
            String senderNickname,
            String status,
            Instant availableAt,
            String claimToken
    ) {
        public static PushOutbox pending(Long targetUserId, String senderNickname) {
            return new PushOutbox(
                    targetUserId, senderNickname, "PENDING", Instant.now(), null
            );
        }
    }
}
