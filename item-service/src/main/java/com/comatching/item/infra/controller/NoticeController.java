package com.comatching.item.infra.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.item.domain.notice.dto.ActiveNoticeResponse;
import com.comatching.item.domain.notice.dto.NoticeCreateRequest;
import com.comatching.item.domain.notice.service.NoticeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Notice API", description = "공지사항 등록 및 조회")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NoticeController {

	private final NoticeService noticeService;

	@RequireRole(MemberRole.ROLE_ADMIN)
	@Operation(summary = "공지사항 등록", description = "관리자가 제목, 내용, 시작시간, 종료시간으로 공지사항을 등록합니다.")
	@PostMapping("/admin/notices")
	public ResponseEntity<ApiResponse<Void>> createNotice(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody @Valid NoticeCreateRequest request
	) {
		noticeService.createNotice(request);
		return ResponseEntity.ok(ApiResponse.ok());
	}

	@RequireRole({MemberRole.ROLE_USER, MemberRole.ROLE_ADMIN})
	@Operation(summary = "활성 공지사항 조회", description = "현재 시각 기준으로 노출 기간에 포함된 공지사항 목록을 조회합니다.")
	@GetMapping("/notices/active")
	public ResponseEntity<ApiResponse<List<ActiveNoticeResponse>>> getActiveNotices(
		@CurrentMember MemberInfo memberInfo
	) {
		return ResponseEntity.ok(ApiResponse.ok(noticeService.getActiveNotices()));
	}
}
