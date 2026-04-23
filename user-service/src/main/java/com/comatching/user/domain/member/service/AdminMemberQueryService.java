package com.comatching.user.domain.member.service;

import java.util.List;

import com.comatching.common.dto.member.AdminUserProfileDto;

public interface AdminMemberQueryService {

	List<AdminUserProfileDto> getUsers(String keyword);

	AdminUserProfileDto getUserDetail(Long memberId);
}
