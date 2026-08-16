package com.comatching.user.domain.admin.service;

import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.response.PagingResponse;
import com.comatching.user.domain.admin.dto.AdminUserDetailResponse;
import com.comatching.user.domain.admin.dto.AdminUserSummaryResponse;

public interface AdminMemberService {

	PagingResponse<AdminUserSummaryResponse> getUsers(String keyword, Pageable pageable);

	AdminUserDetailResponse getUserDetail(Long memberId);

	void updateUserInventory(Long adminId, Long memberId, Object request);
}
