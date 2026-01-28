package com.comatching.chat.domain.dto;

import java.time.LocalDateTime;

import com.comatching.chat.domain.entity.UserBlock;

public record BlockedUserResponse(
	Long userId,
	LocalDateTime blockedAt
) {
	public static BlockedUserResponse from(UserBlock userBlock) {
		return new BlockedUserResponse(
			userBlock.getBlockedUserId(),
			userBlock.getCreatedAt()
		);
	}
}
