package com.comatching.matching.domain.repository.candidate;

import static com.comatching.matching.domain.entity.QMatchingCandidate.*;

import java.util.List;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MatchingCandidateRepositoryImpl implements MatchingCandidateRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public List<MatchingCandidate> findPotentialCandidates(MatchingCandidateSearchCondition condition) {

		return queryFactory
			.selectFrom(matchingCandidate)
			.where(
				matchingCandidate.gender.eq(condition.targetGender()),
				matchingCandidate.isMatchable.isTrue(),
				neMajor(condition.excludeMajor()),
				notInMemberIds(condition.excludeMemberIds()),
				ageGoe(condition.minAge()),
				ageLoe(condition.maxAge()),
				containsAllMbtiTraits(condition.requiredMbtiTraits()),
				eqContactFrequency(condition.requiredContactFrequency()),
				hasHobbyCategory(condition.requiredHobbyCategory()),
				memberIdGt(condition.lastMemberIdExclusive())
			)
			.orderBy(matchingCandidate.memberId.asc())
			.limit(condition.limit())
			.fetch();
	}

	private BooleanExpression neMajor(String major) {
		return major != null ? matchingCandidate.major.ne(major) : null;
	}

	private BooleanExpression notInMemberIds(List<Long> excludeMemberIds) {
		return (excludeMemberIds != null && !excludeMemberIds.isEmpty())
			? matchingCandidate.memberId.notIn(excludeMemberIds)
			: null;
	}

	private BooleanExpression ageGoe(Integer minAge) {
		return minAge != null ? matchingCandidate.age.value.goe(minAge) : null;
	}

	private BooleanExpression ageLoe(Integer maxAge) {
		return maxAge != null ? matchingCandidate.age.value.loe(maxAge) : null;
	}

	private BooleanExpression containsAllMbtiTraits(String traits) {
		if (traits == null || traits.isBlank()) {
			return null;
		}

		BooleanExpression expression = null;
		for (char trait : traits.toCharArray()) {
			BooleanExpression traitExpression = matchingCandidate.mbti.value.contains(String.valueOf(trait));
			expression = expression == null ? traitExpression : expression.and(traitExpression);
		}
		return expression;
	}

	private BooleanExpression eqContactFrequency(ContactFrequency frequency) {
		return frequency != null ? matchingCandidate.contactFrequency.eq(frequency) : null;
	}

	private BooleanExpression hasHobbyCategory(HobbyCategory hobbyCategory) {
		return hobbyCategory != null ? matchingCandidate.hobbyCategories.any().eq(hobbyCategory) : null;
	}

	private BooleanExpression memberIdGt(Long memberId) {
		return memberId != null ? matchingCandidate.memberId.gt(memberId) : null;
	}
}
