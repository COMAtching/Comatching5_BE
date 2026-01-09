package com.comatching.common.dto.event;

import java.time.LocalDateTime;

public record MemberWithdrawnEvent(
	Long memberId,
	String email,
	LocalDateTime withdrawnAt
) {}
