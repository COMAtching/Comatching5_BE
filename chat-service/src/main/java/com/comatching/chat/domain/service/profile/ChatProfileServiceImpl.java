package com.comatching.chat.domain.service.profile;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.chat.domain.dto.ChatMemberProfileResponse;
import com.comatching.chat.infra.client.MemberClient;
import com.comatching.chat.infra.client.MatchingHistoryClient;
import com.comatching.common.dto.matching.MatchingHistoryReferenceResponse;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatProfileServiceImpl implements ChatProfileService {

	private final MemberClient memberClient;
	private final MatchingHistoryClient matchingHistoryClient;

	@Override
	public ChatMemberProfileResponse getMemberProfile(Long currentMemberId, Long targetMemberId) {
		List<ProfileResponse> profiles = memberClient.getProfiles(List.of(targetMemberId));
		if (profiles == null || profiles.isEmpty()) {
			throw new BusinessException(GeneralErrorCode.NOT_FOUND);
		}

		MatchingHistoryReferenceResponse historyReference =
			matchingHistoryClient.getHistoryReference(currentMemberId, targetMemberId);

		return profiles.stream()
			.filter(profile -> targetMemberId.equals(profile.memberId()))
			.findFirst()
			.map(profile -> ChatMemberProfileResponse.from(profile, historyReference))
			.orElseThrow(() -> new BusinessException(GeneralErrorCode.NOT_FOUND));
	}
}
