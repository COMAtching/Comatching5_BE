package com.comatching.member.infra.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.dto.s3.S3UploadResponseDto;
import com.comatching.common.service.S3Service;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/members/files")
@RequiredArgsConstructor
public class S3Controller {

	private final S3Service s3Service;

	@GetMapping("/presigned/profiles")
	public ResponseEntity<ApiResponse<S3UploadResponseDto>> getProfilePresignedUrl(
		@CurrentMember MemberInfo memberInfo,
		@RequestParam String filename) {

		S3UploadResponseDto response = s3Service.getPresignedPutUrl(memberInfo.memberId(), "profiles", filename);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
