package com.comatching.user.infra.controller;

import java.util.List;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.*;
import com.comatching.user.domain.report.MemberReport;
import com.comatching.user.domain.report.ReportService;

@RestController
@RequiredArgsConstructor
public class ReportController {
    private final ReportService service;

    @PostMapping("/api/members/reports")
    @RequireRole(MemberRole.ROLE_USER)
    public ApiResponse<Long> submit(@CurrentMember MemberInfo user, @RequestBody @Valid ReportService.Submission input) {
        return ApiResponse.ok(service.submit(user.memberId(), input));
    }

    @GetMapping("/api/members/me/inbox")
    @RequireRole(MemberRole.ROLE_USER)
    public ApiResponse<PagingResponse<ReportService.InboxItem>> inbox(@CurrentMember MemberInfo user,
                                                        @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok(service.inbox(user.memberId(), page));
    }

    @GetMapping("/api/members/me/inbox/unread-count")
    @RequireRole(MemberRole.ROLE_USER)
    public ApiResponse<Long> unreadCount(@CurrentMember MemberInfo user) {
        return ApiResponse.ok(service.unreadCount(user.memberId()));
    }

    @PostMapping("/api/members/me/inbox/{id}/read")
    @RequireRole(MemberRole.ROLE_USER)
    public ApiResponse<Void> readNotice(@CurrentMember MemberInfo user, @PathVariable long id,
                                        @RequestParam ReportService.NoticeKind kind) {
        service.readNotice(user.memberId(), id, kind);
        return ApiResponse.ok();
    }

    @GetMapping("/api/members/me/warnings")
    @RequireRole(MemberRole.ROLE_USER)
    public ApiResponse<List<ReportService.Warning>> warnings(@CurrentMember MemberInfo user) {
        return ApiResponse.ok(service.warnings(user.memberId()));
    }

    @PostMapping("/api/members/me/warnings/{id}/acknowledge")
    @RequireRole(MemberRole.ROLE_USER)
    public ApiResponse<Void> acknowledge(@CurrentMember MemberInfo user, @PathVariable long id) {
        service.acknowledge(user.memberId(), id);
        return ApiResponse.ok();
    }

    @GetMapping("/api/v1/admin/reports")
    @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<PagingResponse<ReportService.Summary>> list(@CurrentMember MemberInfo admin,
        @RequestParam(required = false) MemberReport.Status status, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok(service.list(status, page));
    }

    @GetMapping("/api/v1/admin/reports/{id}")
    @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<ReportService.Detail> detail(@CurrentMember MemberInfo admin, @PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/api/v1/admin/reports/{id}/decision")
    @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<Void> decide(@CurrentMember MemberInfo admin, @PathVariable long id,
                                     @Valid @RequestBody ReportService.Decision input) {
        service.decide(id, admin.memberId(), input);
        return ApiResponse.ok();
    }

    @PostMapping("/api/v1/admin/reports/{id}/lift")
    @RequireRole(MemberRole.ROLE_ADMIN)
    public ApiResponse<Void> lift(@CurrentMember MemberInfo admin, @PathVariable long id) {
        service.lift(id, admin.memberId());
        return ApiResponse.ok();
    }
}
