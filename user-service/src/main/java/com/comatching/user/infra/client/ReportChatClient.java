package com.comatching.user.infra.client;

import com.comatching.common.dto.chat.ReportChatEvidence;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "report-chat", url = "${chat-service.url:http://localhost:9003}")
public interface ReportChatClient {
    @PostMapping("/api/internal/chat/report-evidence")
    ReportChatEvidence capture(@RequestParam("reporter") long reporter, @RequestParam("target") long target,
                               @RequestBody ReportChatEvidence.Range range);
}
