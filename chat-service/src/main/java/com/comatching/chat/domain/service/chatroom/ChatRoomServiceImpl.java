package com.comatching.chat.domain.service.chatroom;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.chat.domain.dto.ChatRoomResponse;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.repository.UnreadCountCondition;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.common.dto.event.matching.MatchingSuccessEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ChatRoomServiceImpl implements ChatRoomService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final BlockService blockService;

	@Override
	public void createChatRoom(MatchingSuccessEvent event) {

		if (chatRoomRepository.findByMatchingId(event.matchingId()).isPresent()) {
			log.warn("ChatRoom already exists for matchingId: {}", event.matchingId());
			return;
		}

		ChatRoom newRoom = ChatRoom.builder()
			.matchingId(event.matchingId())
			.initiatorUserId(event.initiatorUserId())
			.targetUserId(event.targetUserId())
			.build();

		chatRoomRepository.save(newRoom);

	}

	@Transactional(readOnly = true)
	public List<ChatRoomResponse> getMyChatRooms(Long memberId) {

		Sort sort = Sort.by(Sort.Direction.DESC, "updatedAt");
		Set<Long> blockedUserIds = blockService.getBlockedUserIds(memberId);

		List<ChatRoom> visibleRooms = chatRoomRepository.findMyChatRooms(memberId, sort).stream()
			.filter(room -> {
				Long otherUserId = getOtherUserId(room, memberId);
				return !blockedUserIds.contains(otherUserId);
			})
			.toList();

		Map<String, Long> unreadCountsByRoom = getUnreadCountsByRoom(visibleRooms, memberId);

		return visibleRooms.stream()
			.map(room -> ChatRoomResponse.from(room, unreadCountsByRoom.getOrDefault(room.getId(), 0L)))
			.toList();
	}

	private Long getOtherUserId(ChatRoom room, Long memberId) {
		return memberId.equals(room.getInitiatorUserId())
			? room.getTargetUserId()
			: room.getInitiatorUserId();
	}

	@Override
	@Transactional(readOnly = true)
	public long getTotalUnreadCount(Long memberId) {

		Sort sort = Sort.unsorted();
		List<ChatRoom> myRooms = chatRoomRepository.findMyChatRooms(memberId, sort);
		Set<Long> blockedUserIds = blockService.getBlockedUserIds(memberId);

		List<ChatRoom> visibleRooms = myRooms.stream()
			.filter(room -> {
				Long otherUserId = getOtherUserId(room, memberId);
				return !blockedUserIds.contains(otherUserId);
			})
			.toList();

		return getUnreadCountsByRoom(visibleRooms, memberId).values().stream()
			.mapToLong(Long::longValue)
			.sum();
	}

	private Map<String, Long> getUnreadCountsByRoom(List<ChatRoom> rooms, Long memberId) {
		if (rooms.isEmpty()) {
			return Map.of();
		}

		List<UnreadCountCondition> unreadCountConditions = rooms.stream()
			.map(room -> new UnreadCountCondition(room.getId(), getMyLastReadAt(room, memberId)))
			.toList();

		return chatMessageRepository.countUnreadMessagesByRoom(unreadCountConditions, memberId);
	}

	private LocalDateTime getMyLastReadAt(ChatRoom room, Long memberId) {
		return memberId.equals(room.getInitiatorUserId())
			? room.getInitiatorLastReadAt()
			: room.getTargetLastReadAt();
	}
}
