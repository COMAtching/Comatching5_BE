package com.comatching.chat.domain.dto;

import java.time.LocalDateTime;

import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.common.dto.member.ProfileResponse;

public record ChatRoomResponse(
	String id,
	Long matchingId,
	Long initiatorUserId,
	Long targetUserId,
	UserSummary otherUser,
	String lastMessage,
	LocalDateTime lastMessageTime,
	long unreadCount
) {
	public static ChatRoomResponse from(ChatRoom room, long unreadCount) {
		return from(room, unreadCount, null);
	}

	public static ChatRoomResponse from(ChatRoom room, long unreadCount, UserSummary otherUser) {
		String content = (room.getLastMessageInfo() != null) ? room.getLastMessageInfo().getContent() : null;
		LocalDateTime time = (room.getLastMessageInfo() != null) ? room.getLastMessageInfo().getSentAt() : null;

		return new ChatRoomResponse(
			room.getId(),
			room.getMatchingId(),
			room.getInitiatorUserId(),
			room.getTargetUserId(),
			otherUser,
			content,
			time,
			unreadCount
		);
	}

	public record UserSummary(
		Long memberId,
		String nickname,
		String profileImageUrl,
		String major,
		Integer age
	) {
		public static UserSummary from(ProfileResponse profile) {
			KoreanAge age = KoreanAge.fromBirthDate(profile.birthDate());
			return new UserSummary(
				profile.memberId(),
				profile.nickname(),
				profile.profileImageUrl(),
				profile.major(),
				age != null ? age.getValue() : null
			);
		}

		public static UserSummary memberOnly(Long memberId) {
			return new UserSummary(memberId, null, null, null, null);
		}
	}
}
