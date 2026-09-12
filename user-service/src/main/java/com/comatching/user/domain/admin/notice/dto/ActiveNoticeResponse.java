package com.comatching.user.domain.admin.notice.dto;


import com.comatching.user.domain.admin.notice.entity.Notice;

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
