package com.comatching.chat.domain.dto;

import com.comatching.chat.domain.enums.MessageType;

import lombok.Builder;

@Builder
public record ChatMessageRequest(
	String roomId,
	Long senderId,
	String senderNickname,
	String content,
	MessageType type
) {}
