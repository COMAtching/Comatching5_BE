package com.comatching.chat.domain.dto;

import jakarta.validation.constraints.NotNull;

public record BlockRequest(
	@NotNull(message = "차단할 사용자 ID는 필수입니다.")
	Long targetUserId
) {}
