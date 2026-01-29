package com.comatching.user.domain.member.service;

import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;

public interface ProfileCreateService {

	ProfileResponse createProfile(Long memberId, ProfileCreateRequest request);
}
