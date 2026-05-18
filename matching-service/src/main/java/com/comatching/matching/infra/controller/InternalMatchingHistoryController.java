package com.comatching.matching.infra.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.matching.MatchingHistoryReferenceResponse;
import com.comatching.matching.domain.service.MatchingHistoryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/matching/history")
public class InternalMatchingHistoryController {

	private final MatchingHistoryService matchingHistoryService;

	@GetMapping("/reference")
	public MatchingHistoryReferenceResponse getHistoryReference(
		@RequestParam Long memberId,
		@RequestParam Long partnerId
	) {
		return matchingHistoryService.getHistoryReference(memberId, partnerId);
	}
}
