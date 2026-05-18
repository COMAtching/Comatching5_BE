package com.comatching.matching.domain.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.comatching.common.annotation.DistributedLock;
import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.dto.event.matching.MatchingSuccessEvent;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.dto.item.ItemConsumption;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.matching.domain.component.MatchingItemPolicy;
import com.comatching.matching.domain.component.MatchingProcessor;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.dto.MatchingResponse;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.entity.MatchingCondition;
import com.comatching.matching.domain.entity.MatchingHistory;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.global.exception.MatchingErrorCode;
import com.comatching.matching.infra.client.ItemClient;
import com.comatching.matching.infra.client.MemberClient;
import com.comatching.matching.infra.kafka.MatchingEventProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingServiceImpl implements MatchingService {

	private static final int MAX_ALLOWED_AGE = 27;
	private static final int MAX_MATCHING_ATTEMPTS = 2;
	private static final String PAIR_KEY_CONSTRAINT = "uk_history_pair_key";

	private final MatchingHistoryRepository historyRepository;
	private final MemberClient memberClient;
	private final ItemClient itemClient;
	private final MatchingEventProducer matchingEventProducer;
	private final MatchingItemPolicy matchingItemPolicy;
	private final MatchingProcessor matchingProcessor;

	@Override
	@DistributedLock(key = "MATCHING_REQUEST", identifier = "#memberId", leaseTime = 15L)
	public MatchingResponse match(Long memberId, MatchingRequest request) {
		ProfileResponse myProfile = memberClient.getProfile(memberId);
		validateAgeLimitRequest(request);

		List<ItemConsumption> consumedConsumptions = consumeItems(memberId, request);

		try {
			return matchWithPairConflictRetry(memberId, myProfile, request);
		} catch (Exception e) {
			refundItems(memberId, consumedConsumptions);
			throw e;
		}
	}

	private MatchingResponse matchWithPairConflictRetry(Long memberId, ProfileResponse myProfile, MatchingRequest request) {
		for (int attempt = 1; attempt <= MAX_MATCHING_ATTEMPTS; attempt++) {
			MatchingCandidate matchedCandidate = matchingProcessor.process(memberId, myProfile, request);
			ProfileResponse partnerProfile = memberClient.getProfile(matchedCandidate.getMemberId());

			try {
				saveHistoryAndPublishEvent(memberId, matchedCandidate, request);
				return MatchingResponse.of(matchedCandidate, partnerProfile);
			} catch (DataIntegrityViolationException e) {
				if (!isPairKeyConflict(e)) {
					throw e;
				}
				if (attempt == MAX_MATCHING_ATTEMPTS) {
					throw new BusinessException(MatchingErrorCode.NO_MATCHING_CANDIDATE);
				}
				log.warn(
					"Pair matching conflict detected. retrying matching once. memberId={}, partnerId={}, attempt={}",
					memberId,
					matchedCandidate.getMemberId(),
					attempt
				);
			}
		}

		throw new BusinessException(GeneralErrorCode.INTERNAL_SERVER_ERROR);
	}

	private List<ItemConsumption> consumeItems(Long memberId, MatchingRequest request) {
		List<ItemConsumption> consumptions = matchingItemPolicy.determine(request);
		List<ItemConsumption> consumedConsumptions = new ArrayList<>();

		try {
			for (ItemConsumption consumption : consumptions) {
				itemClient.useItem(memberId, consumption.itemType(), consumption.quantity());
				consumedConsumptions.add(consumption);
			}
		} catch (Exception e) {
			refundItems(memberId, consumedConsumptions);
			throw new BusinessException(MatchingErrorCode.NOT_ENOUGH_ITEM);
		}

		return consumedConsumptions;
	}

	private void refundItems(Long memberId, List<ItemConsumption> consumptions) {
		try {
			for (ItemConsumption consumption : consumptions) {
				AddItemRequest refundReq = new AddItemRequest(
					consumption.itemType(),
					consumption.quantity(),
					ItemRoute.REFUND,
					30
				);
				itemClient.addItem(memberId, refundReq);
			}
		} catch (Exception e) {
			log.error("매칭 실패 후 아이템 환불 실패! memberId={}", memberId, e);
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVER_ERROR);
		}
	}

	private void saveHistoryAndPublishEvent(Long memberId, MatchingCandidate matchedCandidate, MatchingRequest request) {
		MatchingCondition condition = MatchingCondition.builder()
			.ageOption(request.ageOption())
			.minAgeOffset(request.minAgeOffset())
			.maxAgeOffset(request.maxAgeOffset())
			.contactFrequency(request.contactFrequency())
			.hobbyOption(request.hobbyOption())
			.mbtiOption(request.mbtiOption())
			.sameMajorOption(request.sameMajorOption())
			.importantOption(request.importantOption())
			.build();

		MatchingHistory history = MatchingHistory.builder()
			.memberId(memberId)
			.partnerId(matchedCandidate.getMemberId())
			.condition(condition)
			.build();

		MatchingHistory savedHistory = historyRepository.save(history);

		MatchingSuccessEvent event = MatchingSuccessEvent.builder()
			.matchingId(savedHistory.getId())
			.initiatorUserId(memberId)
			.targetUserId(matchedCandidate.getMemberId())
			.build();

		matchingEventProducer.sendMatchingSuccess(event);
	}

	private boolean isPairKeyConflict(DataIntegrityViolationException e) {
		String message = e.getMostSpecificCause() != null
			? e.getMostSpecificCause().getMessage()
			: e.getMessage();
		return message != null && message.toLowerCase().contains(PAIR_KEY_CONSTRAINT);
	}

	private void validateAgeLimitRequest(MatchingRequest request) {
		if (!request.hasAgeLimit()) {
			return;
		}

		if (!request.hasCompleteAgeLimit()) {
			throw new BusinessException(MatchingErrorCode.INVALID_AGE_LIMIT_OPTION);
		}

		int minAge = request.minAgeLimit();
		int maxAge = Math.min(MAX_ALLOWED_AGE, request.maxAgeLimit());
		if (minAge < 0 || maxAge < 0 || minAge > maxAge) {
			throw new BusinessException(MatchingErrorCode.INVALID_AGE_LIMIT_OPTION);
		}
	}
}
