package com.comatching.chat.domain.service.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.chat.domain.dto.ChatMessageRequest;
import com.comatching.chat.domain.dto.ChatMessageResponse;
import com.comatching.chat.domain.entity.ChatMessage;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.infra.kafka.ChatEventProducer;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.service.S3Service;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

	private static final String ROOM_ID = "room-1";
	private static final Long INITIATOR_ID = 1L;
	private static final Long TARGET_ID = 2L;
	private static final Long OUTSIDER_ID = 3L;

	@Mock
	private ChatRoomRepository chatRoomRepository;

	@Mock
	private ChatMessageRepository chatMessageRepository;

	@Mock
	private S3Service s3Service;

	@Mock
	private ChatEventProducer chatEventProducer;

	@Mock
	private BlockService blockService;

	@InjectMocks
	private ChatServiceImpl chatService;

	@Test
	@DisplayName("방 참여자가 아닌 사용자의 TALK 메시지를 거부하고 메시지를 저장하지 않는다")
	void processMessage_rejectsTalkFromNonParticipant() {
		// given
		ChatRoom room = chatRoom(ROOM_ID, INITIATOR_ID, TARGET_ID);
		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));

		ChatMessageRequest request = talkRequest(OUTSIDER_ID, "outsider", "hello");

		// when & then
		assertThatThrownBy(() -> chatService.processMessage(request))
			.isInstanceOfSatisfying(BusinessException.class, e ->
				assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.FORBIDDEN));

		then(chatMessageRepository).should(never()).save(any());
		then(chatRoomRepository).should(never()).updateLastMessageIfLatest(anyString(), anyString(), any());
		then(chatRoomRepository).should(never()).touchLastReadAt(anyString(), anyLong(), any());
		then(blockService).shouldHaveNoInteractions();
		then(chatEventProducer).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("방 참여자가 아닌 사용자의 READ 메시지를 거부하고 readAt을 갱신하지 않는다")
	void processMessage_rejectsReadFromNonParticipant() {
		// given
		ChatRoom room = chatRoom(ROOM_ID, INITIATOR_ID, TARGET_ID);
		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));

		ChatMessageRequest request = readRequest(OUTSIDER_ID);

		// when & then
		assertThatThrownBy(() -> chatService.processMessage(request))
			.isInstanceOfSatisfying(BusinessException.class, e ->
				assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.FORBIDDEN));

		then(chatMessageRepository).should(never()).save(any());
		then(chatRoomRepository).should(never()).touchLastReadAt(anyString(), anyLong(), any());
		then(chatEventProducer).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("정상 TALK 메시지는 저장 후 채팅방 요약과 발신자 readAt만 atomic update 한다")
	void processMessage_updatesRoomSummaryAndReadAtAtomically() {
		// given
		LocalDateTime createdAt = LocalDateTime.of(2026, 5, 16, 10, 0);
		ChatRoom room = chatRoom(ROOM_ID, INITIATOR_ID, TARGET_ID);
		ChatMessage savedMessage = savedMessage("message-1", INITIATOR_ID, "hello", MessageType.TALK, createdAt);

		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
		given(blockService.isBlocked(TARGET_ID, INITIATOR_ID)).willReturn(false);
		given(chatMessageRepository.save(any(ChatMessage.class))).willReturn(savedMessage);

		ChatMessageRequest request = talkRequest(INITIATOR_ID, "sender", "hello");

		// when
		ChatMessageResponse response = chatService.processMessage(request);

		// then
			assertThat(response.id()).isEqualTo("message-1");
			assertThat(response.createdAt()).isEqualTo(createdAt);
			InOrder inOrder = inOrder(chatMessageRepository, chatRoomRepository, chatEventProducer);
			inOrder.verify(chatMessageRepository).save(any(ChatMessage.class));
			inOrder.verify(chatRoomRepository).updateLastMessageIfLatest(ROOM_ID, "hello", createdAt);
			inOrder.verify(chatRoomRepository).touchLastReadAt(ROOM_ID, INITIATOR_ID, createdAt);
			inOrder.verify(chatEventProducer).send(any());
			then(chatRoomRepository).should(never()).save(any(ChatRoom.class));
		}

	@Test
	@DisplayName("차단된 상대에게 보내는 메시지는 저장하지만 채팅방 요약과 알림은 갱신하지 않는다")
	void processMessage_skipsRoomSummaryWhenSenderIsBlockedByReceiver() {
		// given
		LocalDateTime createdAt = LocalDateTime.of(2026, 5, 16, 10, 5);
		ChatRoom room = chatRoom(ROOM_ID, INITIATOR_ID, TARGET_ID);
		ChatMessage savedMessage = savedMessage("message-2", INITIATOR_ID, "blocked", MessageType.TALK, createdAt);

		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
		given(blockService.isBlocked(TARGET_ID, INITIATOR_ID)).willReturn(true);
		given(chatMessageRepository.save(any(ChatMessage.class))).willReturn(savedMessage);

		ChatMessageRequest request = talkRequest(INITIATOR_ID, "sender", "blocked");

		// when
		ChatMessageResponse response = chatService.processMessage(request);

		// then
		assertThat(response.id()).isEqualTo("message-2");
		then(chatMessageRepository).should().save(any(ChatMessage.class));
		then(chatRoomRepository).should(never()).updateLastMessageIfLatest(anyString(), anyString(), any());
		then(chatRoomRepository).should(never()).touchLastReadAt(anyString(), anyLong(), any());
		then(chatEventProducer).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("READ 메시지는 메시지를 저장하지 않고 발신자의 readAt만 atomic update 한다")
	void processMessage_readOnlyTouchesLastReadAt() {
		// given
		ChatRoom room = chatRoom(ROOM_ID, INITIATOR_ID, TARGET_ID);
		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));

		ChatMessageRequest request = readRequest(TARGET_ID);

		// when
		ChatMessageResponse response = chatService.processMessage(request);

		// then
		assertThat(response.roomId()).isEqualTo(ROOM_ID);
		assertThat(response.senderId()).isEqualTo(TARGET_ID);
		assertThat(response.type()).isEqualTo(MessageType.READ);
		then(chatRoomRepository).should().touchLastReadAt(eq(ROOM_ID), eq(TARGET_ID), any(LocalDateTime.class));
		then(chatRoomRepository).should(never()).save(any(ChatRoom.class));
		then(chatMessageRepository).should(never()).save(any());
		then(blockService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("채팅 내역은 최신순 페이지를 조회하되 응답은 오래된 메시지부터 반환한다")
	void getChatHistory_returnsOldestFirstAfterFetchingLatestPage() {
		// given
		ChatRoom room = chatRoom(ROOM_ID, INITIATOR_ID, TARGET_ID);
		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));

		LocalDateTime baseTime = LocalDateTime.of(2026, 5, 16, 10, 0);
		ChatMessage first = savedMessage("message-1", INITIATOR_ID, "1", MessageType.TALK, baseTime.plusMinutes(1));
		ChatMessage second = savedMessage("message-2", TARGET_ID, "2", MessageType.TALK, baseTime.plusMinutes(2));
		ChatMessage third = savedMessage("message-3", INITIATOR_ID, "3", MessageType.TALK, baseTime.plusMinutes(3));
		ChatMessage fourth = savedMessage("message-4", TARGET_ID, "4", MessageType.TALK, baseTime.plusMinutes(4));
		ChatMessage fifth = savedMessage("message-5", INITIATOR_ID, "5", MessageType.TALK, baseTime.plusMinutes(5));
		ChatMessage latest = savedMessage("message-6", TARGET_ID, "6", MessageType.TALK, baseTime.plusMinutes(6));

		given(chatMessageRepository.findByRoomId(eq(ROOM_ID), any(Pageable.class)))
			.willReturn(List.of(latest, fifth, fourth, third, second, first));

		// when
		List<ChatMessageResponse> result = chatService.getChatHistory(ROOM_ID, INITIATOR_ID, PageRequest.of(0, 6));

		// then
		assertThat(result).extracting(ChatMessageResponse::content)
			.containsExactly("1", "2", "3", "4", "5", "6");
		assertThat(result.get(result.size() - 1).id()).isEqualTo("message-6");
		then(chatMessageRepository).should().findByRoomId(
			eq(ROOM_ID),
			argThat(pageable -> pageable.getPageNumber() == 0
				&& pageable.getPageSize() == 6
				&& isDescendingSort(pageable, "createdAt")
				&& isDescendingSort(pageable, "id"))
			);
	}

	@Test
	@DisplayName("상대방 readAt과 같은 시각의 메시지는 읽음 처리한다")
	void getChatHistory_marksMessageAtReadBoundaryAsRead() {
		// given
		LocalDateTime readAt = LocalDateTime.of(2026, 5, 18, 22, 50);
		ChatRoom room = chatRoom(ROOM_ID, INITIATOR_ID, TARGET_ID);
		ReflectionTestUtils.setField(room, "targetLastReadAt", readAt);
		given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));

		ChatMessage beforeRead = savedMessage(
			"message-before",
			INITIATOR_ID,
			"before",
			MessageType.TALK,
			readAt.minusNanos(1)
		);
		ChatMessage atRead = savedMessage("message-at", INITIATOR_ID, "at", MessageType.TALK, readAt);
		ChatMessage afterRead = savedMessage(
			"message-after",
			INITIATOR_ID,
			"after",
			MessageType.TALK,
			readAt.plusNanos(1)
		);

		given(chatMessageRepository.findByRoomId(eq(ROOM_ID), any(Pageable.class)))
			.willReturn(List.of(afterRead, atRead, beforeRead));

		// when
		List<ChatMessageResponse> result = chatService.getChatHistory(ROOM_ID, INITIATOR_ID, PageRequest.of(0, 3));

		// then
		assertThat(result).extracting(ChatMessageResponse::id)
			.containsExactly("message-before", "message-at", "message-after");
		assertThat(result).extracting(ChatMessageResponse::readCount)
			.containsExactly(0, 0, 1);
	}

	private ChatMessageRequest talkRequest(Long senderId, String senderNickname, String content) {
		return new ChatMessageRequest(ROOM_ID, senderId, senderNickname, content, MessageType.TALK);
	}

	private ChatMessageRequest readRequest(Long senderId) {
		return new ChatMessageRequest(ROOM_ID, senderId, "reader", null, MessageType.READ);
	}

	private ChatRoom chatRoom(String id, Long initiatorId, Long targetId) {
		ChatRoom room = ChatRoom.builder()
			.matchingId(100L)
			.initiatorUserId(initiatorId)
			.targetUserId(targetId)
			.build();
		ReflectionTestUtils.setField(room, "id", id);
		return room;
	}

	private ChatMessage savedMessage(
		String id,
		Long senderId,
		String content,
		MessageType type,
		LocalDateTime createdAt
	) {
		ChatMessage message = ChatMessage.builder()
			.roomId(ROOM_ID)
			.senderId(senderId)
			.content(content)
			.type(type)
			.build();
		ReflectionTestUtils.setField(message, "id", id);
		ReflectionTestUtils.setField(message, "createdAt", createdAt);
		return message;
	}

	private boolean isDescendingSort(Pageable pageable, String property) {
		Sort.Order order = pageable.getSort().getOrderFor(property);
		return order != null && order.getDirection() == Sort.Direction.DESC;
	}
}
