package com.comatching.item.domain.notice.service;

import java.util.List;

import com.comatching.item.domain.notice.dto.ActiveNoticeResponse;
import com.comatching.item.domain.notice.dto.NoticeCreateRequest;
import com.comatching.item.domain.notice.dto.NoticeUpdateRequest;

public interface NoticeService {

	void createNotice(NoticeCreateRequest request);

	void updateNotice(Long noticeId, NoticeUpdateRequest request);

	void deleteNotice(Long noticeId);

	List<ActiveNoticeResponse> getActiveNotices();
}
