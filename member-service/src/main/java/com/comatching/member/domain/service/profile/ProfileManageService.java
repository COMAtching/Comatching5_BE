package com.comatching.member.domain.service.profile;

import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.member.domain.dto.ProfileUpdateRequest;

public interface ProfileManageService {

	ProfileResponse getProfile(Long memberId);

	ProfileResponse updateProfile(Long memberId, ProfileUpdateRequest request);
}
