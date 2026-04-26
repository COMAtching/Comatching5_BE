package com.comatching.matching.domain.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.comatching.common.annotation.DistributedLock;
import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.domain.vo.KoreanAge;
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

	private static final int MIN_ALLOWED_AGE = 20;
	private static final int MAX_ALLOWED_AGE = 27;

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
		validateAgeLimitRequest(request, myProfile);

		List<ItemConsumption> consumedConsumptions = consumeItems(memberId, request);

		try {
			MatchingCandidate matchedCandidate = matchingProcessor.process(memberId, myProfile, request);
			ProfileResponse partnerProfile = memberClient.getProfile(matchedCandidate.getMemberId());

			saveHistoryAndPublishEvent(memberId, matchedCandidate, request);

			return MatchingResponse.of(matchedCandidate, partnerProfile);
		} catch (Exception e) {
			refundItems(memberId, consumedConsumptions);
			throw e;
		}
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

	private void validateAgeLimitRequest(MatchingRequest request, ProfileResponse myProfile) {
		if (!request.hasAgeLimit()) {
			return;
		}

		if (!request.hasCompleteAgeLimit()) {
			throw new BusinessException(MatchingErrorCode.INVALID_AGE_LIMIT_OPTION);
		}

		KoreanAge myAge = KoreanAge.fromBirthDate(myProfile.birthDate());
		if (myAge == null) {
			throw new BusinessException(MatchingErrorCode.INVALID_AGE_LIMIT_OPTION);
		}

		int minOffset = request.minAgeOffset();
		int maxOffset = request.maxAgeOffset();
		if (minOffset > maxOffset) {
			throw new BusinessException(MatchingErrorCode.INVALID_AGE_LIMIT_OPTION);
		}

		int minAge = Math.max(MIN_ALLOWED_AGE, myAge.getValue() + minOffset);
		int maxAge = Math.min(MAX_ALLOWED_AGE, myAge.getValue() + maxOffset);
		if (minAge > maxAge) {
			throw new BusinessException(MatchingErrorCode.INVALID_AGE_LIMIT_OPTION);
		}
	}
}
