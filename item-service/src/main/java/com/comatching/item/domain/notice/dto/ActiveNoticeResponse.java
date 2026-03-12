package com.comatching.item.domain.notice.dto;

import com.comatching.item.domain.notice.entity.Notice;

public record ActiveNoticeResponse(
	Long noticeId,
	String title,
	String content
) {
	public static ActiveNoticeResponse from(Notice notice) {
		return new ActiveNoticeResponse(
			notice.getId(),
			notice.getTitle(),
			notice.getContent()
		);
	}
}
