package com.comatching.matching.domain.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.comatching.common.annotation.DistributedLock;
import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.Hobby;
import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.dto.event.matching.MatchingSuccessEvent;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.dto.item.ItemConsumption;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.matching.domain.component.MatchingItemPolicy;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.dto.MatchingResponse;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.entity.MatchingCondition;
import com.comatching.matching.domain.entity.MatchingHistory;
import com.comatching.matching.domain.enums.AgeOption;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
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

	private final MatchingCandidateRepository candidateRepository;
	private final MatchingHistoryRepository historyRepository;
	private final MemberClient memberClient;
	private final ItemClient itemClient;
	private final MatchingEventProducer matchingEventProducer;
	private final MatchingItemPolicy matchingItemPolicy;

	@Override
	@DistributedLock(key = "MATCHING_REQUEST", identifier = "#memberId")
	public MatchingResponse match(Long memberId, MatchingRequest request) {

		ProfileResponse myProfile = memberClient.getProfile(memberId);

		List<ItemConsumption> consumptions = matchingItemPolicy.determine(request);
		List<ItemConsumption> consumedConsumptions = new ArrayList<>();

		try {
			for (ItemConsumption consumption : consumptions) {
				itemClient.useItem(
					memberId,
					consumption.itemType(),
					consumption.quantity()
				);
				consumedConsumptions.add(consumption);
			}
		} catch (Exception e) {
			refundItem(memberId, consumedConsumptions);
			throw new BusinessException(MatchingErrorCode.NOT_ENOUGH_ITEM);
		}

		MatchingCandidate matchedCandidate;
		try {
			matchedCandidate = processMatching(memberId, myProfile, request);
		} catch (Exception e) {
			refundItem(memberId, consumedConsumptions);
			throw e;
		}

		ProfileResponse partnerProfile = memberClient.getProfile(matchedCandidate.getMemberId());

		return MatchingResponse.of(matchedCandidate, partnerProfile);
	}

	private void refundItem(Long memberId, List<ItemConsumption> consumptions) {
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

	private MatchingCandidate processMatching(Long memberId, ProfileResponse myProfile, MatchingRequest request) {

		List<Long> excludeMemberIds = historyRepository.findPartnerIdsByMemberId(memberId);
		String excludeMajor = request.sameMajorOption() ? myProfile.major() : null;
		Gender targetGender = (myProfile.gender() == Gender.MALE) ? Gender.FEMALE : Gender.MALE;
		List<MatchingCandidate> candidates = candidateRepository.findPotentialCandidates(
			targetGender,
			excludeMajor,
			excludeMemberIds
		);
		int myAge = myProfile.birthDate().until(LocalDate.now()).getYears() + 1;

		List<MatchingCandidate> bestCandidates = new ArrayList<>();
		int maxScore = -1;

		for (MatchingCandidate candidate : candidates) {

			if (!isImportantConditionMet(candidate, request, myAge)) {
				continue;
			}

			int score = calculateScore(candidate, request, myAge);

			if (score > maxScore) {
				maxScore = score;
				bestCandidates.clear();
				bestCandidates.add(candidate);
			} else if (score == maxScore) {
				bestCandidates.add(candidate);
			}
		}

		if (bestCandidates.isEmpty()) {
			throw new BusinessException(MatchingErrorCode.NO_MATCHING_CANDIDATE);
		}

		int randomIndex = (int)(Math.random() * bestCandidates.size());
		MatchingCandidate finalPartner = bestCandidates.get(randomIndex);

		MatchingCondition matchingCondition = MatchingCondition.builder()
			.ageOption(request.ageOption())
			.contactFrequency(request.contactFrequency())
			.hobbyOption(request.hobbyOption())
			.mbtiOption(request.mbtiOption())
			.sameMajorOption(request.sameMajorOption())
			.importantOption(request.importantOption())
			.build();

		MatchingHistory matchingHistory = saveMatchingHistory(memberId, finalPartner, matchingCondition);

		MatchingSuccessEvent matchingSuccessEvent = MatchingSuccessEvent.builder()
			.matchingId(matchingHistory.getId())
			.initiatorUserId(memberId)
			.targetUserId(finalPartner.getMemberId())
			.build();

		matchingEventProducer.sendMatchingSuccess(matchingSuccessEvent);

		return finalPartner;
	}

	private boolean isImportantConditionMet(MatchingCandidate candidate, MatchingRequest request, int myAge) {
		String important = request.importantOption();

		if (important == null || important.isBlank()) {
			return true;
		}

		return switch (important) {
			case "ageOption" -> checkAge(candidate.getAge(), myAge, request.ageOption());
			case "mbtiOption" -> checkMbtiStrict(request.mbtiOption(), candidate.getMbti());
			case "hobbyOption" -> checkHobbyStrict(request.hobbyOption(), candidate.getHobbyCategories());
			case "contactOption" -> checkContactStrict(request.contactFrequency(), candidate.getContactFrequency());
			default -> true;
		};
	}

	private boolean checkContactStrict(ContactFrequency reqContact, ContactFrequency candContact) {
		if (reqContact == null) {
			return true;
		}
		if (candContact == null) {
			return false;
		}

		return reqContact.equals(candContact);
	}

	private boolean checkMbtiStrict(String reqMbti, String candMbti) {
		if (reqMbti == null || reqMbti.isBlank()) {
			return true;
		}
		if (candMbti == null) {
			return false;
		}

		String request = reqMbti.toUpperCase();
		String candidate = candMbti.toUpperCase();

		for (char c : request.toCharArray()) {
			if (candidate.indexOf(c) == -1) {
				return false;
			}
		}
		return true;
	}

	private boolean checkHobbyStrict(Hobby.Category reqHobby, List<Hobby.Category> candHobbies) {
		if (reqHobby == null) {
			return true;
		}
		return candHobbies.contains(reqHobby);
	}

	private int calculateScore(MatchingCandidate candidate, MatchingRequest request, int myAge) {
		int score = 0;

		// 1. MBTI 점수
		score += calculateMbtiScore(request.mbtiOption(), candidate.getMbti());

		// 2. 취미 점수
		if (request.hobbyOption() != null && candidate.getHobbyCategories().contains(request.hobbyOption())) {
			score += calculateHobbyScore(candidate.getHobbyCategories(), request.hobbyOption());
		}

		// 3. 나이 점수
		if (checkAge(candidate.getAge(), myAge, request.ageOption())) {
			score += 20;
		}

		if (request.contactFrequency() != null && candidate.getContactFrequency() == request.contactFrequency()) {
			score += 10;
		}

		return score;
	}

	private int calculateMbtiScore(String reqMbti, String candMbti) {

		if (reqMbti == null || reqMbti.isBlank() || candMbti == null) {
			return 0;
		}

		String request = reqMbti.toUpperCase();
		String candidate = candMbti.toUpperCase();

		int matchCount = 0;

		for (char c : request.toCharArray()) {
			if (candidate.indexOf(c) >= 0) {
				matchCount++;
			}
		}

		return matchCount * 10;
	}

	private int calculateHobbyScore(List<Hobby.Category> candidateHobbies, Hobby.Category targetCategory) {

		if (candidateHobbies == null || candidateHobbies.isEmpty()) {
			return 0;
		}

		long matchCount = candidateHobbies.stream()
			.filter(category -> category == targetCategory)
			.count();

		if (matchCount == 0) {
			return 0;
		}

		if (matchCount >= 3) {
			return 20;
		} else if (matchCount == 2) {
			return 15;
		} else {
			return 10;
		}
	}

	private boolean checkAge(int candidateAge, int myAge, AgeOption ageOption) {

		if (ageOption == null) {
			return false;
		}

		return switch (ageOption) {
			case EQUAL -> candidateAge == myAge;
			case OLDER -> candidateAge > myAge;
			case YOUNGER -> candidateAge < myAge;
		};
	}

	private MatchingHistory saveMatchingHistory(Long memberId, MatchingCandidate matchedCandidate,
		MatchingCondition matchingCondition) {
		MatchingHistory history = MatchingHistory.builder()
			.memberId(memberId)
			.partnerId(matchedCandidate.getMemberId())
			.condition(matchingCondition)
			.build();
		return historyRepository.save(history);
	}
}
