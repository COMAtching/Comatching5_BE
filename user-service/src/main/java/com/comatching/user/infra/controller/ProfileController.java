package com.comatching.user.infra.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.ProfileTagCategory;
import com.comatching.common.domain.enums.ProfileTagGroup;
import com.comatching.common.domain.enums.ProfileTagItem;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.user.domain.member.dto.ProfileUpdateRequest;
import com.comatching.user.domain.member.dto.TagCategoryResponse;
import com.comatching.user.domain.member.dto.TagCategoryResponse.TagGroupResponse;
import com.comatching.user.domain.member.dto.TagCategoryResponse.TagItemResponse;
import com.comatching.user.domain.member.service.ProfileCreateService;
import com.comatching.user.domain.member.service.ProfileManageService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProfileController {

	private final ProfileCreateService profileCreateService;
	private final ProfileManageService profileManageService;

	@PostMapping("/internal/users/profile")
	public ProfileResponse createProfile(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestBody ProfileCreateRequest request
	) {
		return profileCreateService.createProfile(memberId, request);
	}

	@PostMapping("/internal/users/profiles/bulk")
	public List<ProfileResponse> getProfilesBulk(@RequestBody List<Long> memberIds) {
		return profileManageService.getProfilesByIds(memberIds);
	}

	@GetMapping("/internal/users/profile")
	public ProfileResponse getProfile(@RequestHeader("X-Member-Id") Long memberId) {
		return profileManageService.getProfile(memberId);
	}

	@RequireRole(MemberRole.ROLE_USER)
	@GetMapping("/members/profile")
	public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(@CurrentMember MemberInfo memberInfo) {
		ProfileResponse response = profileManageService.getProfile(memberInfo.memberId());

		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@RequireRole(MemberRole.ROLE_USER)
	@PatchMapping("/members/profile")
	public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody ProfileUpdateRequest request
	) {
		ProfileResponse response = profileManageService.updateProfile(memberInfo.memberId(), request);

		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@GetMapping("/profile/tags")
	public ResponseEntity<ApiResponse<List<TagCategoryResponse>>> getProfileTags() {
		List<TagCategoryResponse> response = Arrays.stream(ProfileTagCategory.values())
			.map(category -> new TagCategoryResponse(
				category.name(),
				category.getLabel(),
				Arrays.stream(ProfileTagGroup.values())
					.filter(group -> group.getCategory() == category)
					.map(group -> new TagGroupResponse(
						group.name(),
						group.getLabel(),
						Arrays.stream(ProfileTagItem.values())
							.filter(item -> item.getGroup() == group)
							.map(item -> new TagItemResponse(item.name(), item.getLabel()))
							.toList()
					))
					.toList()
			))
			.toList();

		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
