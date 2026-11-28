package com.comatching.chat.infra.controller;

import com.comatching.chat.domain.service.chat.ReportChatEvidenceService;
import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.chat.ReportChatEvidence;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ReportChatEvidenceController {
    private final ReportChatEvidenceService evidence;

    @PostMapping("/api/chat/reports/preview")
    public ApiResponse<ReportChatEvidence> preview(@CurrentMember MemberInfo member, @RequestParam long targetId,
                                                  @Valid @RequestBody ReportChatEvidence.Range range) {
        return ApiResponse.ok(evidence.snapshot(member.memberId(), targetId, range));
    }

    @PostMapping("/api/internal/chat/report-evidence")
    public ReportChatEvidence capture(@RequestParam long reporter, @RequestParam long target,
                                      @Valid @RequestBody ReportChatEvidence.Range range) {
        return evidence.snapshot(reporter, target, range);
    }
}
