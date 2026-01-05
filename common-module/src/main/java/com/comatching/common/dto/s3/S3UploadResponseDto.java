package com.comatching.common.dto.s3;

import lombok.Builder;

@Builder
public record S3UploadResponseDto(
	String presignedUrl,
	String imageKey
) {
}
