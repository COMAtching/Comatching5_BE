package com.comatching.item.domain.notice.dto;

import java.time.LocalDateTime;

import com.comatching.item.domain.notice.entity.Notice;

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
