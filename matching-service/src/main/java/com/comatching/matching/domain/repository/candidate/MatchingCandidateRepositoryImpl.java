package com.comatching.matching.domain.repository.candidate;

import static com.comatching.matching.domain.entity.QMatchingCandidate.*;

import java.util.List;

import com.comatching.common.domain.enums.Gender;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MatchingCandidateRepositoryImpl implements MatchingCandidateRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public List<MatchingCandidate> findPotentialCandidates(
		Gender targetGender,
		String excludeMajor,
		List<Long> excludeMemberIds
	) {

		return queryFactory
			.selectFrom(matchingCandidate)
			.where(
				matchingCandidate.gender.eq(targetGender),
				matchingCandidate.isMatchable.isTrue(),
				neMajor(excludeMajor),
				notInMemberIds(excludeMemberIds)
			)
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
}
