package com.comatching.user.domain.admin.user.service;

import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.response.PagingResponse;
import com.comatching.user.domain.admin.user.dto.AdminInventoryUpdateRequest;
import com.comatching.user.domain.admin.user.dto.AdminUserDetailResponse;
import com.comatching.user.domain.admin.user.dto.AdminUserSummaryResponse;

public interface AdminMemberService {

	PagingResponse<AdminUserSummaryResponse> getUsers(String keyword, Pageable pageable);

	AdminUserDetailResponse getUserDetail(Long memberId);

	void updateUserInventory(Long adminId, Long memberId, AdminInventoryUpdateRequest request);
}
