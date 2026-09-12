package com.comatching.user.domain.member.service;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.member.AdminGiftCardUserProfileDto;
import com.comatching.common.dto.member.AdminUserProfileDto;
import com.comatching.common.dto.response.PagingResponse;

public interface AdminMemberQueryService {

	PagingResponse<AdminUserProfileDto> getUsers(String keyword, Pageable pageable);

	AdminUserProfileDto getUserDetail(Long memberId);

	List<AdminGiftCardUserProfileDto> getUsersByIds(List<Long> memberIds);
}
