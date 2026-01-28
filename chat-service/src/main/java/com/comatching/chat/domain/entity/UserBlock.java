package com.comatching.chat.domain.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "user_blocks")
@CompoundIndexes({
	@CompoundIndex(name = "blocker_blocked_idx", def = "{'blockerUserId': 1, 'blockedUserId': 1}", unique = true)
})
public class UserBlock {

	@Id
	private String id;

	private Long blockerUserId;

	private Long blockedUserId;

	@CreatedDate
	private LocalDateTime createdAt;

	@Builder
	public UserBlock(Long blockerUserId, Long blockedUserId) {
		this.blockerUserId = blockerUserId;
		this.blockedUserId = blockedUserId;
	}
}
