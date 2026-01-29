package com.comatching.user.domain.member.service;

import java.util.List;

import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.user.domain.member.dto.ProfileUpdateRequest;

public interface ProfileManageService {

	ProfileResponse getProfile(Long memberId);

	List<ProfileResponse> getProfilesByIds(List<Long> memberIds);

	ProfileResponse updateProfile(Long memberId, ProfileUpdateRequest request);
}
