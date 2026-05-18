package com.comatching.chat.domain.service.chatroom;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.chat.domain.dto.ChatRoomResponse;
import com.comatching.chat.domain.dto.ChatRoomResponse.UserSummary;
import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.enums.ChatRoomStatus;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.repository.UnreadCountCondition;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.global.exception.ChatErrorCode;
import com.comatching.chat.infra.client.MemberClient;
import com.comatching.common.dto.chat.ChatRoomReferenceResponse;
import com.comatching.common.dto.event.matching.MatchingSuccessEvent;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

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
	private final MemberClient memberClient;

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
		log.info("Created chat room. matchingId={}, roomId={}", event.matchingId(), newRoom.getId());

	}

	@Transactional(readOnly = true)
	public List<ChatRoomResponse> getMyChatRooms(Long memberId) {

		Sort sort = Sort.by(Sort.Direction.DESC, "updatedAt");
		Set<Long> blockedUserIds = blockService.getBlockedUserIds(memberId);

		List<ChatRoom> visibleRooms = chatRoomRepository.findMyChatRooms(memberId, sort).stream()
			.filter(room -> isVisibleToMember(room, memberId))
			.filter(room -> {
				Long otherUserId = getOtherUserId(room, memberId);
				return !blockedUserIds.contains(otherUserId);
			})
			.toList();

		Map<String, Long> unreadCountsByRoom = getUnreadCountsByRoom(visibleRooms, memberId);
		Map<Long, ProfileResponse> profilesByMemberId = getProfilesByMemberId(visibleRooms, memberId);

		return visibleRooms.stream()
			.map(room -> {
				Long otherUserId = getOtherUserId(room, memberId);
				UserSummary otherUser = toUserSummary(otherUserId, profilesByMemberId.get(otherUserId));
				return ChatRoomResponse.from(room, unreadCountsByRoom.getOrDefault(room.getId(), 0L), otherUser);
			})
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
			.filter(room -> isVisibleToMember(room, memberId))
			.filter(room -> {
				Long otherUserId = getOtherUserId(room, memberId);
				return !blockedUserIds.contains(otherUserId);
			})
			.toList();

		return getUnreadCountsByRoom(visibleRooms, memberId).values().stream()
			.mapToLong(Long::longValue)
			.sum();
	}

	@Override
	@Transactional(readOnly = true)
	public void validateRoomMember(String roomId, Long memberId) {
		ChatRoom room = chatRoomRepository.findById(roomId)
			.orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_EXIST_CHATROOM));

		if (!isRoomMember(room, memberId)) {
			throw new BusinessException(GeneralErrorCode.FORBIDDEN);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<ChatRoomReferenceResponse> getChatRoomReferencesByMatchingIds(List<Long> matchingIds) {
		if (matchingIds == null || matchingIds.isEmpty()) {
			return List.of();
		}

		return chatRoomRepository.findByMatchingIdIn(matchingIds).stream()
			.map(room -> new ChatRoomReferenceResponse(room.getMatchingId(), room.getId()))
			.toList();
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

	private Map<Long, ProfileResponse> getProfilesByMemberId(List<ChatRoom> rooms, Long memberId) {
		if (rooms.isEmpty()) {
			return Map.of();
		}

		List<Long> otherUserIds = rooms.stream()
			.map(room -> getOtherUserId(room, memberId))
			.distinct()
			.toList();

		List<ProfileResponse> profiles = memberClient.getProfiles(otherUserIds);
		if (profiles == null || profiles.isEmpty()) {
			return Map.of();
		}

		return profiles.stream()
			.collect(Collectors.toMap(ProfileResponse::memberId, Function.identity(), (left, right) -> left));
	}

	private UserSummary toUserSummary(Long memberId, ProfileResponse profile) {
		if (profile == null) {
			return UserSummary.memberOnly(memberId);
		}
		return UserSummary.from(profile);
	}

	private LocalDateTime getMyLastReadAt(ChatRoom room, Long memberId) {
		return memberId.equals(room.getInitiatorUserId())
			? room.getInitiatorLastReadAt()
			: room.getTargetLastReadAt();
	}

	private boolean isRoomMember(ChatRoom room, Long memberId) {
		return room.getInitiatorUserId().equals(memberId) || room.getTargetUserId().equals(memberId);
	}

	private boolean isVisibleToMember(ChatRoom room, Long memberId) {
		if (memberId.equals(room.getInitiatorUserId())) {
			return true;
		}
		return memberId.equals(room.getTargetUserId()) && room.getStatus() == ChatRoomStatus.ACTIVE;
	}
}
