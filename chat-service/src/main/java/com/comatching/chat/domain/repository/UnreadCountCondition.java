package com.comatching.chat.domain.repository;

import java.time.LocalDateTime;

public record UnreadCountCondition(
	String roomId,
	LocalDateTime lastReadAt
) {
}
