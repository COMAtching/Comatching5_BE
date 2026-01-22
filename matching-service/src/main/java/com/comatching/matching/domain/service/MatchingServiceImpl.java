package com.comatching.matching.domain.service;

import java.util.ArrayList;
import java.util.List;

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
import com.comatching.matching.infra.ItemClient;
import com.comatching.matching.infra.client.MemberClient;
import com.comatching.matching.infra.kafka.MatchingEventProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingServiceImpl implements MatchingService {

	private final MatchingHistoryRepository historyRepository;
	private final MemberClient memberClient;
	private final ItemClient itemClient;
	private final MatchingEventProducer matchingEventProducer;
	private final MatchingItemPolicy matchingItemPolicy;
	private final MatchingProcessor matchingProcessor;

	@Override
	@DistributedLock(key = "MATCHING_REQUEST", identifier = "#memberId")
	public MatchingResponse match(Long memberId, MatchingRequest request) {
		ProfileResponse myProfile = memberClient.getProfile(memberId);

		List<ItemConsumption> consumedConsumptions = consumeItems(memberId, request);

		MatchingCandidate matchedCandidate;
		try {
			matchedCandidate = matchingProcessor.process(memberId, myProfile, request);
		} catch (Exception e) {
			refundItems(memberId, consumedConsumptions);
			throw e;
		}

		saveHistoryAndPublishEvent(memberId, matchedCandidate, request);

		ProfileResponse partnerProfile = memberClient.getProfile(matchedCandidate.getMemberId());
		return MatchingResponse.of(matchedCandidate, partnerProfile);
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
}
