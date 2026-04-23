package com.comatching.item.domain.notice.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NoticeUpdateRequest(
	@NotBlank(message = "제목은 필수입니다.")
	@Size(max = 200, message = "제목은 200자 이하로 입력해주세요.")
	String title,

	@NotBlank(message = "내용은 필수입니다.")
	String content,

	@NotNull(message = "시작시간은 필수입니다.")
	LocalDateTime startTime,

	@NotNull(message = "종료시간은 필수입니다.")
	LocalDateTime endTime
) {
}
