package com.comatching.matching.domain.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.chat.ChatRoomEnsureRequest;
import com.comatching.common.dto.chat.ChatRoomReferenceResponse;
import com.comatching.common.dto.matching.MatchingHistoryReferenceResponse;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.matching.domain.dto.MatchingHistoryResponse;
import com.comatching.matching.domain.entity.MatchingHistory;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.global.exception.MatchingErrorCode;
import com.comatching.matching.infra.client.ChatRoomClient;
import com.comatching.matching.infra.client.MemberClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class MatchingHistoryServiceImpl implements MatchingHistoryService{

	private final MatchingHistoryRepository historyRepository;
	private final MemberClient memberClient;
	private final ChatRoomClient chatRoomClient;

	@Override
	@Transactional(readOnly = true)
	public PagingResponse<MatchingHistoryResponse> getMyMatchingHistory(
		Long memberId,
		LocalDateTime startDate,
		LocalDateTime endDate,
		Pageable pageable,
		boolean favoriteOnly) {

		Page<MatchingHistory> histories;

		if (favoriteOnly) {
			histories = historyRepository.searchFavoriteHistory(memberId, startDate, endDate, pageable);
		}else {
			histories = historyRepository.searchHistory(memberId, startDate, endDate, pageable);
		}

		if (histories.isEmpty()) {
			return PagingResponse.from(Page.empty(pageable));
		}

		List<Long> partnerIds = histories.stream()
			.map(MatchingHistory::getPartnerId)
			.distinct()
			.toList();

		List<ProfileResponse> profiles = memberClient.getProfiles(partnerIds);

		Map<Long, ProfileResponse> profileMap = profiles.stream()
			.collect(Collectors.toMap(ProfileResponse::memberId, p -> p));
		Map<Long, String> chatRoomIdMap = getChatRoomIdMap(histories);

		Page<MatchingHistoryResponse> resultPage = histories.map(history -> {
			ProfileResponse partnerProfile = profileMap.get(history.getPartnerId());
			String chatRoomId = history.getId() != null ? chatRoomIdMap.get(history.getId()) : null;
			return MatchingHistoryResponse.of(history, partnerProfile, chatRoomId);
		});

		return PagingResponse.from(resultPage);
	}

	// 조회가 곧 보장이다. 방 생성은 매칭 성공 Kafka 이벤트에만 의존했는데,
	// 발행이 유실되면 그 매칭은 영구히 방이 없었다. ensure 는 방이 없으면
	// chat-service 가 그 자리에서 만들므로 유실분이 이력 조회 시점에 복구된다.
	private Map<Long, String> getChatRoomIdMap(Page<MatchingHistory> histories) {
		List<ChatRoomEnsureRequest> ensureRequests = histories.stream()
			.filter(history -> history.getId() != null)
			.map(history -> new ChatRoomEnsureRequest(
				history.getId(),
				history.getMemberId(),
				history.getPartnerId()
			))
			.distinct()
			.toList();

		if (ensureRequests.isEmpty()) {
			return Map.of();
		}

		List<ChatRoomReferenceResponse> chatRooms = chatRoomClient.ensureChatRooms(ensureRequests);
		if (chatRooms == null || chatRooms.isEmpty()) {
			return Map.of();
		}

		return chatRooms.stream()
			.filter(chatRoom -> chatRoom.matchingId() != null)
			.collect(Collectors.toMap(
				ChatRoomReferenceResponse::matchingId,
				ChatRoomReferenceResponse::chatRoomId,
				(left, right) -> left
			));
	}

	@Override
	public void changeFavorite(Long memberId, Long historyId, boolean favorite) {
		MatchingHistory matchingHistory = historyRepository.findById(historyId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.NOT_EXIST_HISTORY));

		if (!matchingHistory.getMemberId().equals(memberId)) {
			throw new BusinessException(GeneralErrorCode.FORBIDDEN);
		}

		matchingHistory.updateFavorite(favorite);
	}

	@Override
	@Transactional(readOnly = true)
	public MatchingHistoryReferenceResponse getHistoryReference(Long memberId, Long partnerId) {
		return historyRepository.findByMemberIdAndPartnerId(memberId, partnerId)
			.map(history -> new MatchingHistoryReferenceResponse(history.getId(), history.isFavorite()))
			.orElseGet(MatchingHistoryReferenceResponse::empty);
	}
}
