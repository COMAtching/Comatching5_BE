package com.comatching.matching.domain.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.matching.domain.dto.MatchingHistoryResponse;
import com.comatching.matching.domain.entity.MatchingHistory;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.global.exception.MatchingErrorCode;
import com.comatching.matching.infra.client.MemberClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class MatchingHistoryServiceImpl implements MatchingHistoryService{

	private final MatchingHistoryRepository historyRepository;
	private final MemberClient memberClient;

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

		Page<MatchingHistoryResponse> resultPage = histories.map(history -> {
			ProfileResponse partnerProfile = profileMap.get(history.getPartnerId());
			return MatchingHistoryResponse.of(history, partnerProfile);
		});

		return PagingResponse.from(resultPage);
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
}
