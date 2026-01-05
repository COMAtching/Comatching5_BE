package com.comatching.common.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;

import java.net.MalformedURLException;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.dto.s3.S3UploadResponseDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import io.awspring.cloud.s3.S3Template;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

	@Mock
	private S3Template s3Template;

	@Mock
	private S3Presigner s3Presigner;

	@InjectMocks
	private S3Service s3Service;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(s3Service, "bucketName", "test-bucket");
	}

	@Test
	void getPresignedPutUrl_Success() throws MalformedURLException {
		//given
		String dirName = "profiles";
		String filename = "test-image.jpg";
		String expectedUrl = "https://s3.ap-northeast-2.amazonaws.com/test-bucket/profiles/uuid.jpg";

		PresignedPutObjectRequest presignedRequest = mock(PresignedPutObjectRequest.class);
		given(presignedRequest.url()).willReturn(new URL(expectedUrl));
		given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).willReturn(presignedRequest);

		//when
		S3UploadResponseDto response = s3Service.getPresignedPutUrl(dirName, filename);

		//then
		assertThat(response.presignedUrl()).isEqualTo(expectedUrl);
		assertThat(response.imageKey()).startsWith("profiles/");
		assertThat(response.imageKey()).endsWith(".jpg");
		assertThat(response.imageKey().split("/")[1].length()).isGreaterThan(10);
	}

	@Test
	void getPresignedPutUrl_Fail_NoExtension() {
		// given
		String filename = "image";

		// when & then
		assertThatThrownBy(() -> s3Service.getPresignedPutUrl("profiles", filename))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException) ex).getErrorCode())
			.isEqualTo(GeneralErrorCode.INVALID_INPUT_VALUE);
	}

	@Test
	void getPresignedPutUrl_Fail_InvalidExtension() {
		// given
		String filename = "script.sh";

		// when & then
		assertThatThrownBy(() -> s3Service.getPresignedPutUrl("profiles", filename))
			.isInstanceOf(BusinessException.class)
			.hasMessageContaining("지원하지 않는 이미지 형식")
			.extracting(ex -> ((BusinessException) ex).getErrorCode())
			.isEqualTo(GeneralErrorCode.INVALID_INPUT_VALUE);
	}

	@Test
	void deleteFile_Success() {
		// given
		String imageKey = "profiles/abc-123.jpg";

		// when
		s3Service.deleteFile(imageKey);

		// then
		verify(s3Template, times(1)).deleteObject("test-bucket", imageKey);
	}

	@Test
	void deleteFile_Fail_S3Error() {
		// given
		String imageKey = "profiles/error.jpg";
		doThrow(new RuntimeException("AWS S3 Error")).when(s3Template).deleteObject(anyString(), anyString());

		// when & then
		assertThatThrownBy(() -> s3Service.deleteFile(imageKey))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException) ex).getErrorCode())
			.isEqualTo(GeneralErrorCode.INTERNAL_SERVER_ERROR);
	}
}