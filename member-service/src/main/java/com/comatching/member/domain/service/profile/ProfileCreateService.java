package com.comatching.member.domain.service.profile;

import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;

public interface ProfileCreateService {

	ProfileResponse createProfile(Long memberId, ProfileCreateRequest request);
}
