package com.comatching.item.domain.notice.service;

import java.util.List;

import com.comatching.item.domain.notice.dto.ActiveNoticeResponse;
import com.comatching.item.domain.notice.dto.NoticeCreateRequest;

public interface NoticeService {

	void createNotice(NoticeCreateRequest request);

	List<ActiveNoticeResponse> getActiveNotices();
}
