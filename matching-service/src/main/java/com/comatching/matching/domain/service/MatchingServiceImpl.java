package com.comatching.matching.domain.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.annotation.DistributedLock;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.matching.domain.dto.MatchingHistoryResponse;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.dto.MatchingResponse;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.entity.MatchingHistory;
import com.comatching.matching.domain.enums.AgeOption;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.global.exception.MatchingErrorCode;
import com.comatching.matching.infra.client.MemberClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchingServiceImpl implements MatchingService {

	private final MatchingCandidateRepository candidateRepository;
	private final MatchingHistoryRepository historyRepository;
	private final MemberClient memberClient;

	@Override
	@DistributedLock(key = "MATCHING_REQUEST", identifier = "#memberId")
	public MatchingResponse match(Long memberId, MatchingRequest request) {

		ProfileResponse myProfile = memberClient.getProfile(memberId);

		MatchingCandidate matchedCandidate = processMatching(memberId, myProfile, request);

		ProfileResponse partnerProfile = memberClient.getProfile(matchedCandidate.getMemberId());

		return MatchingResponse.of(matchedCandidate, partnerProfile);
	}

	@Override
	@Transactional(readOnly = true)
	public Page<MatchingHistoryResponse> getMyMatchingHistory(Long memberId, Pageable pageable) {
		return historyRepository.findByMemberIdOrderByMatchedAtDesc(memberId, pageable)
			.map(MatchingHistoryResponse::from);
	}

	@Transactional
	protected MatchingCandidate processMatching(Long memberId, ProfileResponse myProfile, MatchingRequest request) {
		List<Long> excludedIds = historyRepository.findPartnerIdsByMemberId(memberId);
		if (excludedIds == null)
			excludedIds = new ArrayList<>();

		Gender targetGender = myProfile.gender().equals(Gender.MALE) ? Gender.FEMALE : Gender.MALE;
		String excludeMajor = request.sameMajorOption() ? myProfile.major() : null;
		int myAge = myProfile.birthDate().until(LocalDate.now()).getYears() + 1;

		List<MatchingCandidate> candidates = candidateRepository.findPotentialCandidates(
			targetGender, excludeMajor, excludedIds, 100
		);

		if (candidates.isEmpty()) {
			throw new BusinessException(MatchingErrorCode.NO_MATCHING_CANDIDATE);
		}

		Collections.shuffle(candidates);

		MatchingCandidate bestCandidate = candidates.stream()
			.max(Comparator.comparingInt(c -> calculateScore(c, request, myAge)))
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.NO_MATCHING_CANDIDATE));

		return saveMatchingHistory(memberId, bestCandidate);
	}

	private int calculateScore(MatchingCandidate candidate, MatchingRequest request, int myAge) {
		int score = 0;

		// 1. MBTI 점수
		score += calculateMbtiScore(request.mbtiOption(), candidate.getMbti());

		// 2. 취미 점수 (20점)
		if (request.hobbyOption() != null && candidate.getHobbyCategories().contains(request.hobbyOption())) {
			score += 20;
		}

		// 3. 나이 점수 (20점)
		if (checkAge(candidate.getAge(), myAge, request.ageOption())) {
			score += 20;
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

	private MatchingCandidate saveMatchingHistory(Long memberId, MatchingCandidate matchedCandidate) {
		MatchingHistory history = MatchingHistory.builder()
			.memberId(memberId)
			.partnerId(matchedCandidate.getMemberId())
			.build();
		historyRepository.save(history);

		return matchedCandidate;
	}
}
