package com.comatching.chat.domain.service.chatroom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.chat.infra.client.MemberClient;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

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

	@Mock
	private MemberClient memberClient;

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
		given(memberClient.getProfiles(anyList()))
			.willReturn(List.of(
				profile(OTHER_MEMBER_ID, "첫번째상대", "https://img.example/first.png", "코매칭대", LocalDate.now().minusYears(23)),
				profile(SECOND_OTHER_MEMBER_ID, "두번째상대", "https://img.example/second.png", "매칭대", LocalDate.now().minusYears(24))
			));

		// when
		List<ChatRoomResponse> result = chatRoomService.getMyChatRooms(MEMBER_ID);

		// then
		assertThat(result).extracting(ChatRoomResponse::unreadCount)
			.containsExactly(2L, 3L);
		ChatRoomResponse.UserSummary firstOtherUser = result.get(0).otherUser();
		assertThat(firstOtherUser.memberId()).isEqualTo(OTHER_MEMBER_ID);
		assertThat(firstOtherUser.nickname()).isEqualTo("첫번째상대");
		assertThat(firstOtherUser.profileImageUrl()).isEqualTo("https://img.example/first.png");
		assertThat(firstOtherUser.university()).isEqualTo("코매칭대");
		assertThat(firstOtherUser.age()).isEqualTo(24);
		then(chatMessageRepository).should().countUnreadMessagesByRoom(
			argThat(conditions -> containsCondition(conditions, "room-1", firstReadAt)
				&& containsCondition(conditions, "room-2", secondReadAt)),
			eq(MEMBER_ID)
		);
		then(memberClient).should().getProfiles(List.of(OTHER_MEMBER_ID, SECOND_OTHER_MEMBER_ID));
		then(chatMessageRepository).should(never()).countUnreadMessages(anyString(), any(), anyLong());
	}

	@Test
	@DisplayName("채팅방 목록에서 차단된 상대는 프로필 조회 대상에서 제외한다")
	void getMyChatRooms_excludesBlockedRoomsFromProfileLookup() {
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
		given(memberClient.getProfiles(anyList()))
			.willReturn(List.of(
				profile(OTHER_MEMBER_ID, "보이는상대", "https://img.example/visible.png", "코매칭대", LocalDate.now().minusYears(22))
			));

		// when
		List<ChatRoomResponse> result = chatRoomService.getMyChatRooms(MEMBER_ID);

		// then
		assertThat(result).hasSize(1);
		assertThat(result.get(0).otherUser().memberId()).isEqualTo(OTHER_MEMBER_ID);
		then(memberClient).should().getProfiles(List.of(OTHER_MEMBER_ID));
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

	@Test
	@DisplayName("방 참여자는 파일 업로드 권한 검증을 통과한다")
	void validateRoomMember_allowsParticipant() {
		// given
		ChatRoom room = chatRoom("room-1", 100L, MEMBER_ID, OTHER_MEMBER_ID, LocalDateTime.now());
		given(chatRoomRepository.findById("room-1")).willReturn(Optional.of(room));

		// when & then
		assertThatCode(() -> chatRoomService.validateRoomMember("room-1", MEMBER_ID))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("방 참여자가 아니면 파일 업로드 권한 검증을 거부한다")
	void validateRoomMember_rejectsNonParticipant() {
		// given
		ChatRoom room = chatRoom("room-1", 100L, MEMBER_ID, OTHER_MEMBER_ID, LocalDateTime.now());
		given(chatRoomRepository.findById("room-1")).willReturn(Optional.of(room));

		// when & then
		assertThatThrownBy(() -> chatRoomService.validateRoomMember("room-1", SECOND_OTHER_MEMBER_ID))
			.isInstanceOfSatisfying(BusinessException.class, e ->
				assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.FORBIDDEN));
	}

	@Test
	@DisplayName("존재하지 않는 방이면 파일 업로드 권한 검증을 404로 거부한다")
	void validateRoomMember_rejectsMissingRoom() {
		// given
		given(chatRoomRepository.findById("missing-room")).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> chatRoomService.validateRoomMember("missing-room", MEMBER_ID))
			.isInstanceOfSatisfying(BusinessException.class, e ->
				assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.NOT_EXIST_CHATROOM));
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

	private ProfileResponse profile(
		Long memberId,
		String nickname,
		String profileImageUrl,
		String university,
		LocalDate birthDate
	) {
		return ProfileResponse.builder()
			.memberId(memberId)
			.nickname(nickname)
			.profileImageUrl(profileImageUrl)
			.university(university)
			.birthDate(birthDate)
			.build();
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
