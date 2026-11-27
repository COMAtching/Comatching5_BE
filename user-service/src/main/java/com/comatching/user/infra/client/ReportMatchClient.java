package com.comatching.user.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "report-matching", url = "${matching-service.url:http://localhost:9001}")
public interface ReportMatchClient {
    @GetMapping("/api/internal/matching/history/partners")
    boolean arePartners(@RequestParam("memberId") long memberId, @RequestParam("partnerId") long partnerId);
}
