package com.comatching.chat.domain.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import com.comatching.chat.domain.enums.MessageType;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "chat_messages")
@CompoundIndexes({
	@CompoundIndex(name = "room_created_idx", def = "{'roomId': 1, 'createdAt': -1}")
})
public class ChatMessage {

	@Id
	private String id;

	private String roomId;

	private Long senderId;

	private String content;

	private MessageType type;

	@CreatedDate
	private LocalDateTime createdAt;

	@Builder
	public ChatMessage(String roomId, Long senderId, String content, MessageType type) {
		this.roomId = roomId;
		this.senderId = senderId;
		this.content = content;
		this.type = type;
	}
}
