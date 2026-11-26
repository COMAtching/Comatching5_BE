package com.comatching.common.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

@ExtendWith(MockitoExtension.class)
class S3ServiceTest {
	private static final String OBJECT = "8b601b50-e1da-4b56-bb44-d9522f78fc8d.png";
	private static final String PROFILE = "pending-uploads/profiles/7/" + OBJECT;
	private static final String CHAT = "pending-uploads/chat/room-2/7/" + OBJECT;
	private static final String URL = "https://fixture.s3.ap-northeast-2.amazonaws.com";

	@Mock S3Client storage;
	@Mock StringRedisTemplate redis;
	private final ObjectMapper json = new ObjectMapper();
	private S3Service service;

	@BeforeEach
	void setUp() {
		var credentials = StaticCredentialsProvider.create(AwsSessionCredentials.create("fixture", "fixture-secret", "fixture-session"));
		service = new S3Service(storage, new S3PostPolicy(credentials, json), redis, "fixture", "ap-northeast-2", "", 30);
	}

	@Test
	void postPoliciesBindKeyTypeTemporaryCredentialsAndPurposeSpecificSize() throws Exception {
		given(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of("image:issuance:{7}")), eq("30")))
			.willReturn(1L);
		var profile = service.issueProfile(7, "picture.PNG");
		var chat = service.issueChat(7, "room-2", "chat.gif");
		assertThat(profile.imageKey()).startsWith("pending-uploads/profiles/7/").endsWith(".png");
		assertThat(chat.imageKey()).startsWith("pending-uploads/chat/room-2/7/").endsWith(".gif");
		assertThat(profile.maxBytes()).isEqualTo(10L << 20);
		assertThat(chat.maxBytes()).isEqualTo(20L << 20);
		for (var form : List.of(profile, chat)) {
			assertThat(form.uploadUrl()).isEqualTo(URL + "/");
			var doc = json.readTree(Base64.getDecoder().decode(form.fields().get("policy")));
			Instant signedAt = Instant.parse(doc.get("expiration").asText()).minusSeconds(300);
			assertThat(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX").withZone(ZoneOffset.UTC).format(signedAt))
				.isEqualTo(form.fields().get("x-amz-date"));
			var conditions = doc.get("conditions");
			assertThat(conditions).contains(json.valueToTree(Map.of("bucket", "fixture")),
				json.valueToTree(Map.of("key", form.imageKey())),
				json.valueToTree(Map.of("x-amz-security-token", "fixture-session")),
				json.readTree("[\"content-length-range\",1," + form.maxBytes() + "]"));
			form.fields().forEach((name, value) -> {
				if (!name.equals("policy") && !name.equals("x-amz-signature"))
					assertThat(conditions).contains(json.valueToTree(Map.of(name, value)));
			});
			assertThat(form.fields().get("x-amz-signature")).matches("[a-f0-9]{64}");
		}
	}

	@Test
	void quotaIsSharedAcrossProfileAndChatAndInvalidInputNeverIssuesAPolicy() {
		assertThatThrownBy(() -> service.issueProfile(7, "not-an-image.txt")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.issueChat(7, "../another", "file.jpg")).isInstanceOf(BusinessException.class);
		verifyNoInteractions(redis);
		given(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of("image:issuance:{7}")), eq("30")))
			.willReturn(0L);
		assertThatThrownBy(() -> service.issueChat(7, "room-2", "file.jpg")).isInstanceOfSatisfying(
			BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.DELIVERY_RATE_LIMITED));
	}

	@Test
	void policiesCanOnlyAttachToTheirIssuingAccountAndRoom() {
		assertThatThrownBy(() -> service.attachProfile(8, PROFILE)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.attachChat(8, "room-2", CHAT)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.attachChat(7, "room-3", CHAT)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.attachProfile(7, CHAT)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.attachProfile(7, URL + "/profiles/7/" + OBJECT)).isInstanceOf(BusinessException.class);
		verifyNoInteractions(storage);
	}

	@Test
	void promotionRejectsAnInvalidStoredObjectAndMakesAConditionalCopyForAValidOne() {
		var source = HeadObjectResponse.builder().contentType("image/png").lastModified(Instant.now()).eTag("\"abc\"");
		given(storage.headObject(any(HeadObjectRequest.class))).willReturn(
			source.contentLength((10L << 20) + 1).build(), source.contentLength(9L).contentType("text/plain").build(),
			source.contentType("image/png").build());
		assertThatThrownBy(() -> service.attachProfile(7, PROFILE)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.attachProfile(7, PROFILE)).isInstanceOf(BusinessException.class);
		verify(storage, never()).copyObject(any(CopyObjectRequest.class));
		assertThat(service.attachProfile(7, PROFILE)).isEqualTo(URL + "/profiles/7/" + OBJECT);
		verify(storage).copyObject(ArgumentMatchers.<CopyObjectRequest>argThat(r ->
			r.copySourceIfMatch().equals("\"abc\"") && r.destinationKey().equals("profiles/7/" + OBJECT)
				&& r.overrideConfiguration().orElseThrow().headers().get("If-None-Match").contains("*")));
	}

	@Test
	void replayCannotOverwriteAnExistingMessageWithDifferentBytes() {
		var source = HeadObjectResponse.builder().contentType("image/png").contentLength(20L).lastModified(Instant.now());
		given(storage.headObject(any(HeadObjectRequest.class))).willReturn(
			source.eTag("\"new\"").build(), source.metadata(Map.of("upload-etag", "original")).build());
		given(storage.copyObject(any(CopyObjectRequest.class))).willThrow(S3Exception.builder().statusCode(412).build());
		assertThatThrownBy(() -> service.attachChat(7, "room-2", CHAT)).isInstanceOf(BusinessException.class);
	}

	@Test
	void sweepKeepsLinkedImagesAndPurgesBothPartsOfAbandonedPromotions() {
		String other = PROFILE.replace("/7/", "/8/");
		var pages = mock(ListObjectsV2Iterable.class);
		Instant yesterday = Instant.now().minusSeconds(90000);
		var objects = List.of(S3Object.builder().key(PROFILE).lastModified(yesterday).build(),
			S3Object.builder().key(other).lastModified(yesterday).build(),
			S3Object.builder().key(PROFILE.replace("/7/", "/9/")).lastModified(Instant.now()).build());
		given(storage.listObjectsV2Paginator(any(ListObjectsV2Request.class))).willReturn(pages);
		given(pages.contents()).willReturn(objects::iterator);
		service.cleanupExpired(S3Service.Purpose.PROFILE, (owner, image) -> owner.equals("7"));
		var deleted = org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
		verify(storage, times(3)).deleteObject(deleted.capture());
		assertThat(deleted.getAllValues()).extracting(DeleteObjectRequest::key)
			.containsExactlyInAnyOrder(PROFILE, other, "profiles/8/" + OBJECT);
	}
}
