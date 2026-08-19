package com.comatching.user.domain.admin.notice.service;



import com.comatching.user.domain.admin.notice.dto.ActiveNoticeResponse;
import com.comatching.user.domain.admin.notice.dto.AdminNoticeResponse;
import com.comatching.user.domain.admin.notice.dto.NoticeCreateRequest;
import com.comatching.user.domain.admin.notice.dto.NoticeUpdateRequest;

import java.util.List;

public interface AdminNoticeService {

	void createNotice(NoticeCreateRequest request);

	void updateNotice(Long noticeId, NoticeUpdateRequest request);

	void deleteNotice(Long noticeId);

	List<ActiveNoticeResponse> getActiveNotices();

	List<AdminNoticeResponse> getAdminNotices();
}
