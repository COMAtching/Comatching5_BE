package com.comatching.matching.domain.component;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.comatching.common.domain.vo.KoreanAge;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.enums.ImportantOption;
import com.comatching.matching.domain.vo.Mbti;

@Component
public class ImportantConditionCheckerFactory {

	private final Map<ImportantOption, ImportantConditionChecker> checkers;

	public ImportantConditionCheckerFactory() {
		this.checkers = new EnumMap<>(ImportantOption.class);
		this.checkers.put(ImportantOption.AGE, this::checkAge);
		this.checkers.put(ImportantOption.MBTI, this::checkMbti);
		this.checkers.put(ImportantOption.HOBBY, this::checkHobby);
		this.checkers.put(ImportantOption.CONTACT, this::checkContact);
	}

	public boolean check(ImportantOption option, MatchingCandidate candidate,
		MatchingRequest request, KoreanAge myAge) {
		if (option == null) {
			return true;
		}
		return checkers.getOrDefault(option, (c, r, a) -> true)
			.check(candidate, request, myAge);
	}

	private boolean checkAge(MatchingCandidate candidate, MatchingRequest request, KoreanAge myAge) {
		if (request.ageOption() == null || candidate.getAge() == null || myAge == null) {
			return false;
		}
		return switch (request.ageOption()) {
			case EQUAL -> candidate.getAge().isEqual(myAge);
			case OLDER -> candidate.getAge().isOlderThan(myAge);
			case YOUNGER -> candidate.getAge().isYoungerThan(myAge);
		};
	}

	private boolean checkMbti(MatchingCandidate candidate, MatchingRequest request, KoreanAge myAge) {
		return candidate.getMbti().containsAll(new Mbti(request.mbtiOption()));
	}

	private boolean checkHobby(MatchingCandidate candidate, MatchingRequest request, KoreanAge myAge) {
		return request.hobbyOption() == null || candidate.hasHobbyCategory(request.hobbyOption());
	}

	private boolean checkContact(MatchingCandidate candidate, MatchingRequest request, KoreanAge myAge) {
		return candidate.matchesContactFrequency(request.contactFrequency());
	}
}
