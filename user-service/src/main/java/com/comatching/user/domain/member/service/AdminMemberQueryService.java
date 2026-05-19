package com.comatching.user.domain.member.service;

import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;

public interface AdminMemberQueryService {

	PagingResponse<AdminUserProfileDto> getUsers(String keyword, Pageable pageable);

	AdminUserProfileDto getUserDetail(Long memberId);
}
