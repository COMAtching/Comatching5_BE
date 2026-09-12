package com.comatching.user.domain.admin.notice.dto;


import com.comatching.user.domain.admin.notice.entity.Notice;

import java.time.LocalDateTime;

public record AdminNoticeResponse(
	Long noticeId,
	String title,
	String content,
	LocalDateTime startTime,
	LocalDateTime endTime,
	boolean active
) {
	public static AdminNoticeResponse from(Notice notice, LocalDateTime currentTime) {
		return new AdminNoticeResponse(
			notice.getId(),
			notice.getTitle(),
			notice.getContent(),
			notice.getStartTime(),
			notice.getEndTime(),
			isActive(notice, currentTime)
		);
	}

	private static boolean isActive(Notice notice, LocalDateTime currentTime) {
		return !notice.getStartTime().isAfter(currentTime) && !notice.getEndTime().isBefore(currentTime);
	}
}
