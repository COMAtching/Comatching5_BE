package com.comatching.chat.domain.service.chat;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.common.service.S3Service;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class ChatImageCleanup {
	private final S3Service images;
	private final ChatMessageRepository messages;

	@Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
	void removeExpiredUploads() {
		images.cleanupExpired(S3Service.Purpose.CHAT,
			(roomId, objectPath) -> messages.existsByRoomIdAndTypeAndContentEndingWith(roomId, MessageType.IMAGE, objectPath));
	}
}
