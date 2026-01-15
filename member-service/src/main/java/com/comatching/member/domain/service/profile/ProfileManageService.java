package com.comatching.member.domain.service.profile;

import java.util.List;

import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.member.domain.dto.ProfileUpdateRequest;

public interface ProfileManageService {

	ProfileResponse getProfile(Long memberId);

	List<ProfileResponse> getProfilesByIds(List<Long> memberIds);

	ProfileResponse updateProfile(Long memberId, ProfileUpdateRequest request);
}
