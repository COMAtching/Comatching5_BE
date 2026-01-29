package com.comatching.user.domain.auth.dto;

import com.comatching.common.dto.member.ProfileResponse;

import lombok.Builder;

@Builder
public record CompleteSignupResponse(
	ProfileResponse profile,
	boolean isOnboardingFinished
) {
}
