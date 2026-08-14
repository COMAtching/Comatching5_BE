package com.comatching.user.domain.admin.service;

import com.comatching.user.domain.admin.dto.AdminUserSummaryResponse;
import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;

/**
 * TODO(refactor/admin): 반환 타입을 item-service 원본 스펙(AdminUserSummaryResponse/AdminUserDetailResponse,
 * 티켓 수량 포함)으로 교체. 해당 DTO가 common-module로 이관된 뒤 적용.
 */
public interface AdminMemberService {

	PagingResponse<AdminUserSummaryResponse> getUsers(String keyword, Pageable pageable);

	AdminUserProfileDto getUserDetail(Long memberId);

	void updateUserInventory(Long adminId, Long memberId, Object request);
}
