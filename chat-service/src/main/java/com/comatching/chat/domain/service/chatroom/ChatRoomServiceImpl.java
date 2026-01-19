package com.comatching.chat.domain.service.chatroom;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.chat.domain.dto.ChatRoomResponse;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
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

		return chatRoomRepository.findMyChatRooms(memberId, sort).stream()
			.map(room -> {
				LocalDateTime myLastRead = (memberId.equals(room.getInitiatorUserId()))
					? room.getInitiatorLastReadAt()
					: room.getTargetLastReadAt();

				// 안 읽은 개수 쿼리
				long count = chatMessageRepository.countUnreadMessages(room.getId(), myLastRead, memberId);

				return (ChatRoomResponse.from(room, count));
			})
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public long getTotalUnreadCount(Long memberId) {

		Sort sort = Sort.unsorted();
		List<ChatRoom> myRooms = chatRoomRepository.findMyChatRooms(memberId, sort);

		long totalUnread = 0;
		for (ChatRoom room : myRooms) {
			LocalDateTime myReadTime = (memberId.equals(room.getInitiatorUserId()))
				? room.getInitiatorLastReadAt()
				: room.getTargetLastReadAt();

			totalUnread += chatMessageRepository.countUnreadMessages(room.getId(), myReadTime, memberId);
		}

		return totalUnread;
	}
}
