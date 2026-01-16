package com.comatching.chat.domain.dto;

import java.time.LocalDateTime;

import com.comatching.chat.domain.entity.ChatMessage;
import com.comatching.chat.domain.enums.MessageType;

public record ChatMessageResponse(
	String id,
	String roomId,
	Long senderId,
	String content,
	MessageType type,
	LocalDateTime createdAt,
	int readCount
) {
	public static ChatMessageResponse from(ChatMessage message, int readCount) {
		return new ChatMessageResponse(
			message.getId(),
			message.getRoomId(),
			message.getSenderId(),
			message.getContent(),
			message.getType(),
			message.getCreatedAt(),
			readCount
		);
	}
}
