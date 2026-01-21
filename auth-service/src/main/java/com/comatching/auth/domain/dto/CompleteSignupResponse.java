package com.comatching.auth.domain.dto;

import com.comatching.common.dto.member.ProfileResponse;

import lombok.Builder;

@Builder
public record CompleteSignupResponse(
	ProfileResponse profile,
	boolean isOnboardingFinished
) {
}
