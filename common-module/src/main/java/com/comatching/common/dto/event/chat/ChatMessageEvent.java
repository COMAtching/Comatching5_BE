package com.comatching.common.dto.event.chat;

public record ChatMessageEvent(
	Long targetUserId,
	String senderNickname,
	String roomId,
	String content,
	String timestamp,
	String type
) {}
