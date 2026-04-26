package com.comatching.chat.domain.service.chatroom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.chat.domain.dto.ChatRoomResponse;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.repository.UnreadCountCondition;
import com.comatching.chat.domain.service.block.BlockService;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceImplTest {

	private static final Long MEMBER_ID = 1L;
	private static final Long OTHER_MEMBER_ID = 2L;
	private static final Long SECOND_OTHER_MEMBER_ID = 3L;

	@Mock
	private ChatRoomRepository chatRoomRepository;

	@Mock
	private ChatMessageRepository chatMessageRepository;

	@Mock
	private BlockService blockService;

	@InjectMocks
	private ChatRoomServiceImpl chatRoomService;

	@Test
	@DisplayName("채팅방 목록의 안 읽은 메시지 수를 한 번에 조회한다")
	void getMyChatRooms_batchesUnreadCounts() {
		// given
		LocalDateTime firstReadAt = LocalDateTime.of(2026, 1, 1, 12, 0);
		LocalDateTime secondReadAt = LocalDateTime.of(2026, 1, 1, 13, 0);
		ChatRoom firstRoom = chatRoom("room-1", 100L, MEMBER_ID, OTHER_MEMBER_ID, firstReadAt);
		ChatRoom secondRoom = chatRoom("room-2", 101L, MEMBER_ID, SECOND_OTHER_MEMBER_ID, secondReadAt);

		given(chatRoomRepository.findMyChatRooms(eq(MEMBER_ID), any(Sort.class)))
			.willReturn(List.of(firstRoom, secondRoom));
		given(blockService.getBlockedUserIds(MEMBER_ID)).willReturn(Set.of());
		given(chatMessageRepository.countUnreadMessagesByRoom(anyList(), eq(MEMBER_ID)))
			.willReturn(Map.of("room-1", 2L, "room-2", 3L));

		// when
		List<ChatRoomResponse> result = chatRoomService.getMyChatRooms(MEMBER_ID);

		// then
		assertThat(result).extracting(ChatRoomResponse::unreadCount)
			.containsExactly(2L, 3L);
		then(chatMessageRepository).should().countUnreadMessagesByRoom(
			argThat(conditions -> containsCondition(conditions, "room-1", firstReadAt)
				&& containsCondition(conditions, "room-2", secondReadAt)),
			eq(MEMBER_ID)
		);
		then(chatMessageRepository).should(never()).countUnreadMessages(anyString(), any(), anyLong());
	}

	@Test
	@DisplayName("전체 안 읽은 메시지 수 조회에서 차단된 방을 제외하고 배치 합산한다")
	void getTotalUnreadCount_batchesVisibleRooms() {
		// given
		LocalDateTime firstReadAt = LocalDateTime.of(2026, 1, 1, 12, 0);
		LocalDateTime blockedReadAt = LocalDateTime.of(2026, 1, 1, 13, 0);
		ChatRoom visibleRoom = chatRoom("room-1", 100L, MEMBER_ID, OTHER_MEMBER_ID, firstReadAt);
		ChatRoom blockedRoom = chatRoom("room-2", 101L, MEMBER_ID, SECOND_OTHER_MEMBER_ID, blockedReadAt);

		given(chatRoomRepository.findMyChatRooms(eq(MEMBER_ID), any(Sort.class)))
			.willReturn(List.of(visibleRoom, blockedRoom));
		given(blockService.getBlockedUserIds(MEMBER_ID)).willReturn(Set.of(SECOND_OTHER_MEMBER_ID));
		given(chatMessageRepository.countUnreadMessagesByRoom(anyList(), eq(MEMBER_ID)))
			.willReturn(Map.of("room-1", 7L));

		// when
		long result = chatRoomService.getTotalUnreadCount(MEMBER_ID);

		// then
		assertThat(result).isEqualTo(7L);
		then(chatMessageRepository).should().countUnreadMessagesByRoom(
			argThat(conditions -> conditions.size() == 1
				&& containsCondition(conditions, "room-1", firstReadAt)
				&& !containsRoom(conditions, "room-2")),
			eq(MEMBER_ID)
		);
		then(chatMessageRepository).should(never()).countUnreadMessages(anyString(), any(), anyLong());
	}

	private ChatRoom chatRoom(String id, Long matchingId, Long initiatorId, Long targetId, LocalDateTime readAt) {
		ChatRoom room = ChatRoom.builder()
			.matchingId(matchingId)
			.initiatorUserId(initiatorId)
			.targetUserId(targetId)
			.build();

		ReflectionTestUtils.setField(room, "id", id);
		ReflectionTestUtils.setField(room, "initiatorLastReadAt", readAt);
		return room;
	}

	private boolean containsCondition(List<UnreadCountCondition> conditions, String roomId, LocalDateTime lastReadAt) {
		return conditions.stream()
			.anyMatch(condition -> roomId.equals(condition.roomId()) && lastReadAt.equals(condition.lastReadAt()));
	}

	private boolean containsRoom(List<UnreadCountCondition> conditions, String roomId) {
		return conditions.stream()
			.anyMatch(condition -> roomId.equals(condition.roomId()));
	}
}
