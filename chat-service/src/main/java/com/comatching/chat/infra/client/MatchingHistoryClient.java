package com.comatching.chat.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.comatching.common.dto.matching.MatchingHistoryReferenceResponse;

@FeignClient(name = "matching-service", path = "/api/internal/matching/history", url = "${matching-service.url}")
public interface MatchingHistoryClient {

	@GetMapping("/reference")
	MatchingHistoryReferenceResponse getHistoryReference(
		@RequestParam Long memberId,
		@RequestParam Long partnerId
	);
}
