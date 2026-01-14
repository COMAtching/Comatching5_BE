package com.comatching.matching.infra.controller;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.matching.domain.dto.MatchingHistoryResponse;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.dto.MatchingResponse;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.service.MatchingService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/matching")
@RequiredArgsConstructor
public class MatchingController {

	private final MatchingService matchingService;

	@RequireRole(MemberRole.ROLE_USER)
	@PostMapping
	public ResponseEntity<ApiResponse<MatchingResponse>> match(
		@CurrentMember MemberInfo memberInfo,
		@RequestBody MatchingRequest request
	) {
		MatchingResponse result = matchingService.match(memberInfo.memberId(), request);
		return ResponseEntity.ok(ApiResponse.ok(result));
	}

	@GetMapping("/history")
	public ResponseEntity<ApiResponse<PagingResponse<MatchingHistoryResponse>>> getHistory(
		@RequestHeader("X-Member-Id") Long memberId,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
		@PageableDefault(size = 10, sort = "matchedAt", direction = Sort.Direction.DESC) Pageable pageable
	) {
		PagingResponse<MatchingHistoryResponse> result = matchingService.getMyMatchingHistory(memberId, startDate, endDate, pageable);
		return ResponseEntity.ok(ApiResponse.ok(result));
	}
}
