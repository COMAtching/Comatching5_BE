package com.comatching.chat.domain.dto;

import java.time.LocalDateTime;

import com.comatching.chat.domain.entity.ChatRoom;

public record ChatRoomResponse(
	String id,
	Long matchingId,
	Long initiatorUserId,
	Long targetUserId,
	String lastMessage,
	LocalDateTime lastMessageTime,
	long unreadCount
) {
	public static ChatRoomResponse from(ChatRoom room, long unreadCount) {
		String content = (room.getLastMessageInfo() != null) ? room.getLastMessageInfo().getContent() : null;
		LocalDateTime time = (room.getLastMessageInfo() != null) ? room.getLastMessageInfo().getSentAt() : null;

		return new ChatRoomResponse(
			room.getId(),
			room.getMatchingId(),
			room.getInitiatorUserId(),
			room.getTargetUserId(),
			content,
			time,
			unreadCount
		);
	}
}
