package com.comatching.chat.domain.repository;

import java.time.LocalDateTime;

public interface ChatRoomRepositoryCustom {

	boolean touchLastReadAt(String roomId, Long memberId, LocalDateTime readAt);

	boolean updateLastMessageIfLatest(String roomId, String previewContent, LocalDateTime sentAt);
}
