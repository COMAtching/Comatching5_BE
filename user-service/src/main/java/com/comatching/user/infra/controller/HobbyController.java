package com.comatching.user.infra.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.member.HobbyCategoryResponse;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.user.domain.member.service.EnumLookupService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/hobbies")
@RequiredArgsConstructor
public class HobbyController {

	private final EnumLookupService enumLookupService;

	@GetMapping("/categories")
	public ResponseEntity<ApiResponse<List<HobbyCategoryResponse>>> getCategories() {
		List<HobbyCategoryResponse> response = enumLookupService.getHobbyCategories();
		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
