package com.comatching.common.dto.s3;

import java.util.Map;

/** Form fields must be sent unmodified, followed by the file, to the POST endpoint. */
public record S3UploadResponseDto(String uploadUrl, Map<String, String> fields, String imageKey, long maxBytes) {}
