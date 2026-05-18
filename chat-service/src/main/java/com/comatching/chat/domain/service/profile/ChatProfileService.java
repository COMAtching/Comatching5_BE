package com.comatching.chat.domain.service.profile;

import com.comatching.chat.domain.dto.ChatMemberProfileResponse;

public interface ChatProfileService {

	ChatMemberProfileResponse getMemberProfile(Long currentMemberId, Long targetMemberId);
}
