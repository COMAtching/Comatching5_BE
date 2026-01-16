package com.comatching.chat.domain.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import com.comatching.chat.domain.enums.ChatRoomStatus;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(collection = "chat_rooms")
public class ChatRoom {

	@Id
	private String id;

	@Indexed(unique = true)
	private Long matchingId;

	private Long initiatorUserId;
	private Long targetUserId;

	private ChatRoomStatus status;

	private LastMessageInfo lastMessageInfo;

	private LocalDateTime initiatorLastReadAt;
	private LocalDateTime targetLastReadAt;

	@CreatedDate
	private LocalDateTime createdAt;

	@LastModifiedDate
	private LocalDateTime updatedAt;

	@Builder
	public ChatRoom(Long matchingId, Long initiatorUserId, Long targetUserId) {
		this.matchingId = matchingId;
		this.initiatorUserId = initiatorUserId;
		this.targetUserId = targetUserId;
		this.status = ChatRoomStatus.WAITING;
		this.lastMessageInfo = null;
		this.initiatorLastReadAt = LocalDateTime.now();
		this.targetLastReadAt = LocalDateTime.now();
	}

	public void updateLastMessageInfo(String content, LocalDateTime sendTime) {
		if (this.status == ChatRoomStatus.WAITING) {
			this.status = ChatRoomStatus.ACTIVE;
		}
		updateLastMessage(content, sendTime);
	}

	public void updateLastReadAt(Long memberId, LocalDateTime time) {
		if (memberId.equals(initiatorUserId)) {
			this.initiatorLastReadAt = time;
		} else if (memberId.equals(targetUserId)) {
			this.targetLastReadAt = time;
		}
	}

	public LocalDateTime getOtherUserLastReadAt(Long myMemberId) {
		if (myMemberId.equals(initiatorUserId)) {
			return targetLastReadAt;
		}
		return initiatorLastReadAt;
	}

	public void updateLastMessage(String content, LocalDateTime sendTime) {
		this.lastMessageInfo = new LastMessageInfo(content, sendTime);
	}

	@Getter
	@NoArgsConstructor
	public static class LastMessageInfo {
		private String content;
		private LocalDateTime sentAt;

		public LastMessageInfo(String content, LocalDateTime sentAt) {
			this.content = content;
			this.sentAt = sentAt;
		}
	}

}
