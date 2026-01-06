package com.comatching.member.domain.service;

import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;

public interface ProfileService {

	ProfileResponse createProfile(Long memberId, ProfileCreateRequest request);
}
