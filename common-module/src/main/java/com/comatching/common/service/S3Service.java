package com.comatching.common.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.comatching.common.dto.s3.S3UploadResponseDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import io.awspring.cloud.s3.S3Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

	private final S3Template s3Template;
	private final S3Presigner s3Presigner;

	@Value("${spring.cloud.aws.s3.bucket}")
	private String bucketName;

	@Value("${spring.cloud.aws.region.static}")
	private String region;

	/**
	 * Presigned URL 발급 (파일 업로드용)
	 *
	 * @param dirName          S3 내 디렉토리 이름
	 * @param originalFilename 클라이언트가 올릴 원본 파일명
	 */
	public S3UploadResponseDto getPresignedPutUrl(Long memberId, String dirName, String originalFilename) {
		String extension = validateImageExtension(originalFilename);
		String contentType = determineContentType(extension);

		String imageKey = dirName + "/" + memberId + "/" + UUID.randomUUID() + "." + extension;

		return createPresignedUrlResponse(imageKey, contentType);
	}

	/**
	 * 채팅방 이미지 업로드용
	 * 경로: chat/{roomId}/{uuid}.{ext}
	 */
	public S3UploadResponseDto getPresignedPutUrlForChat(String roomId, String originalFilename) {
		String extension = validateImageExtension(originalFilename);
		String contentType = determineContentType(extension);

		String imageKey = "chat/" + roomId + "/" + UUID.randomUUID() + "." + extension;

		return createPresignedUrlResponse(imageKey, contentType);
	}

	/**
	 * S3 파일 삭제
	 *
	 * @param imageKey 삭제할 파일의 키 (예: profiles/abc.jpg)
	 */
	public void deleteFile(String imageKey) {
		if (imageKey == null || imageKey.isBlank())
			return;
		try {
			s3Template.deleteObject(bucketName, imageKey);
		} catch (Exception e) {
			log.error("S3 File Delete Error: {}", imageKey, e);
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVER_ERROR, "이미지 삭제 중 오류가 발생했습니다.");
		}
	}

	private S3UploadResponseDto createPresignedUrlResponse(String imageKey, String contentType) {
		PutObjectRequest objectRequest = PutObjectRequest.builder()
			.bucket(bucketName)
			.key(imageKey)
			.contentType(contentType)
			.build();

		PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
			.signatureDuration(Duration.ofMinutes(5))
			.putObjectRequest(objectRequest)
			.build();

		String presignedUrl = s3Presigner.presignPutObject(presignRequest).url().toString();

		return S3UploadResponseDto.builder()
			.presignedUrl(presignedUrl)
			.imageKey(imageKey)
			.contentType(contentType)
			.build();
	}

	private String determineContentType(String extension) {
		return switch (extension) {
			case "jpg", "jpeg" -> "image/jpeg";
			case "png" -> "image/png";
			case "gif" -> "image/gif";
			case "webp" -> "image/webp";
			default -> "application/octet-stream";
		};
	}

	private String validateImageExtension(String filename) {
		int lastDotIndex = filename.lastIndexOf(".");
		if (lastDotIndex == -1) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "파일 확장자가 없습니다.");
		}

		String extension = filename.substring(lastDotIndex + 1).toLowerCase();
		if (!extension.equals("jpg") && !extension.equals("jpeg") &&
			!extension.equals("png") && !extension.equals("gif")) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "지원하지 않는 이미지 형식입니다.");
		}
		return extension;
	}

	/**
	 * S3 파일 키를 이용해 퍼블릭 이미지 URL을 생성합니다.
	 *
	 * @param imageKey DB에 저장된 파일 키 (예: profiles/uuid.jpg)
	 * @return 접근 가능한 Full URL
	 */
	public String getFileUrl(String imageKey) {
		if (imageKey == null || imageKey.isBlank()) {
			return null;
		}
		return "https://" + bucketName + ".s3." + region + ".amazonaws.com/" + imageKey;
	}
}
