package com.comatching.user.infra.controller;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.user.domain.admin.notice.dto.ActiveNoticeResponse;
import com.comatching.user.domain.admin.notice.dto.AdminNoticeResponse;
import com.comatching.user.domain.admin.notice.dto.NoticeCreateRequest;
import com.comatching.user.domain.admin.notice.dto.NoticeUpdateRequest;
import com.comatching.user.domain.admin.notice.service.AdminNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Notice API", description = "공지사항 등록/수정/삭제 및 조회")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AdminNoticeController {
    private final AdminNoticeService adminNoticeService;

    @RequireRole(MemberRole.ROLE_ADMIN)
    @Operation(summary = "관리자 공지사항 목록 조회", description = "관리자가 등록된 전체 공지사항을 노출 시작시간 내림차순으로 조회합니다.")
    @GetMapping("v1/admin/notices")
    public ResponseEntity<ApiResponse<List<AdminNoticeResponse>>> getAdminNotices(
            @CurrentMember MemberInfo memberInfo
    ) {
        return ResponseEntity.ok(ApiResponse.ok(adminNoticeService.getAdminNotices()));
    }

    @RequireRole(MemberRole.ROLE_ADMIN)
    @Operation(summary = "공지사항 등록", description = "관리자가 제목, 내용, 시작시간, 종료시간으로 공지사항을 등록합니다.")
    @PostMapping("v1/admin/notices")
    public ResponseEntity<ApiResponse<Void>> createNotice(
            @CurrentMember MemberInfo memberInfo,
            @RequestBody @Valid NoticeCreateRequest request
    ) {
        adminNoticeService.createNotice(request);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @RequireRole(MemberRole.ROLE_ADMIN)
    @Operation(summary = "공지사항 수정", description = "관리자가 기존 공지사항의 제목, 내용, 노출 기간을 수정합니다.")
    @PatchMapping("v1/admin/notices/{noticeId}")
    public ResponseEntity<ApiResponse<Void>> updateNotice(
            @CurrentMember MemberInfo memberInfo,
            @PathVariable Long noticeId,
            @RequestBody @Valid NoticeUpdateRequest request
    ) {
        adminNoticeService.updateNotice(noticeId, request);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @RequireRole(MemberRole.ROLE_ADMIN)
    @Operation(summary = "공지사항 삭제", description = "관리자가 공지사항을 삭제합니다.")
    @DeleteMapping("v1/admin/notices/{noticeId}")
    public ResponseEntity<ApiResponse<Void>> deleteNotice(
            @CurrentMember MemberInfo memberInfo,
            @PathVariable Long noticeId
    ) {
        adminNoticeService.deleteNotice(noticeId);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @RequireRole({MemberRole.ROLE_USER, MemberRole.ROLE_ADMIN})
    @Operation(summary = "활성 공지사항 조회", description = "현재 시각 기준으로 노출 기간에 포함된 공지사항 목록을 조회합니다.")
    @GetMapping("/notices/active")
    public ResponseEntity<ApiResponse<List<ActiveNoticeResponse>>> getActiveNotices(
            @CurrentMember MemberInfo memberInfo
    ) {
        return ResponseEntity.ok(ApiResponse.ok(adminNoticeService.getActiveNotices()));
    }
}
