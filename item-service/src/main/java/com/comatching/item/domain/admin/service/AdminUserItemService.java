package com.comatching.item.domain.admin.service;

import java.util.List;

import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;
import com.comatching.item.domain.admin.dto.AdminUserDetailResponse;
import com.comatching.item.domain.admin.dto.AdminUserSummaryResponse;

public interface AdminUserItemService {

	List<AdminUserSummaryResponse> getUsers(String keyword);

	AdminUserDetailResponse getUserDetail(Long memberId);

	void updateUserInventory(Long memberId, AdminInventoryUpdateRequest request);
}
