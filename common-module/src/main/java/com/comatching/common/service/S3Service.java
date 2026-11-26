package com.comatching.common.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.comatching.common.dto.s3.S3UploadResponseDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Slf4j
@Service
public class S3Service {
	public enum Purpose {
		PROFILE("profiles", 10L << 20), CHAT("chat", 20L << 20);

		private final String directory;
		private final long maxBytes;

		Purpose(String directory, long maxBytes) {
			this.directory = directory;
			this.maxBytes = maxBytes;
		}
	}

	private static final String TEMPORARY = "pending-uploads/";
	private static final Duration ATTACH_WINDOW = Duration.ofHours(2);
	private static final Duration CLEANUP_AGE = Duration.ofDays(1);
	private static final Pattern ROOM = Pattern.compile("[a-zA-Z0-9_-]{1,128}");
	private static final Pattern MEMBER = Pattern.compile("[1-9][0-9]{0,18}");
	private static final Pattern OBJECT = Pattern.compile(
		"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png|gif)");
	private static final Map<String, String> TYPES = Map.of(
		"jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "gif", "image/gif");
	private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
		local count = tonumber(redis.call('GET', KEYS[1]) or '0')
		if count >= tonumber(ARGV[1]) then return 0 end
		if redis.call('INCR', KEYS[1]) == 1 then redis.call('EXPIRE', KEYS[1], 3600) end
		return 1
		""", Long.class);

	private final S3Client s3;
	private final S3PostPolicy policies;
	private final StringRedisTemplate redis;
	private final String bucket;
	private final String region;
	private final String baseUrl;
	private final int hourlyLimit;

	public S3Service(S3Client s3, S3PostPolicy policies, StringRedisTemplate redis,
		@Value("${spring.cloud.aws.s3.bucket}") String bucket,
		@Value("${spring.cloud.aws.region.static}") String region,
		@Value("${comatching.uploads.bucket-url:}") String bucketUrl,
		@Value("${comatching.uploads.hourly-limit:30}") int hourlyLimit) {
		this.s3 = s3;
		this.policies = policies;
		this.redis = redis;
		this.bucket = bucket;
		this.region = region;
		this.hourlyLimit = hourlyLimit;
		this.baseUrl = bucketUrl.isBlank() ? "https://" + bucket + ".s3." + region + ".amazonaws.com"
			: bucketUrl.replaceFirst("/+$", "");
	}

	public S3UploadResponseDto issueProfile(long memberId, String filename) {
		return issue(Purpose.PROFILE, memberId, "profiles/" + memberId + "/", filename);
	}

	public S3UploadResponseDto issueChat(long memberId, String roomId, String filename) {
		return issue(Purpose.CHAT, memberId, chatDirectory(memberId, roomId), filename);
	}

	private S3UploadResponseDto issue(Purpose purpose, long memberId, String directory, String filename) {
		String extension = extension(filename);
		try {
			Long granted = redis.execute(RESERVE, List.of("image:issuance:{" + memberId + "}"), Integer.toString(hourlyLimit));
			if (granted == null) throw unavailable();
			if (granted == 0) throw new BusinessException(GeneralErrorCode.DELIVERY_RATE_LIMITED, "이미지 업로드 요청이 너무 많습니다.");
		} catch (DataAccessException e) {
			throw unavailable();
		}
		String key = TEMPORARY + directory + UUID.randomUUID() + "." + extension;
		return new S3UploadResponseDto(baseUrl + "/",
			policies.sign(bucket, region, key, TYPES.get(extension), purpose.maxBytes, Instant.now()), key, purpose.maxBytes);
	}

	public String attachProfile(long memberId, String key) {
		return attach(Purpose.PROFILE, "profiles/" + memberId + "/", key);
	}

	public String attachChat(long memberId, String roomId, String key) {
		return attach(Purpose.CHAT, chatDirectory(memberId, roomId), key);
	}

	private String attach(Purpose purpose, String directory, String key) {
		String prefix = TEMPORARY + directory;
		if (key == null || !key.startsWith(prefix) || !OBJECT.matcher(key.substring(prefix.length())).matches()) {
			throw invalidImage();
		}
		String destination = key.substring(TEMPORARY.length());
		String contentType = TYPES.get(extension(key.substring(prefix.length())));
		try {
			HeadObjectResponse source = head(key);
			if (!contentType.equals(source.contentType()) || source.contentLength() == null || source.contentLength() < 1
				|| source.contentLength() > purpose.maxBytes || source.eTag() == null) throw invalidImage();
			if (source.lastModified() == null || source.lastModified().isBefore(Instant.now().minus(ATTACH_WINDOW))) {
				throw new BusinessException(GeneralErrorCode.FILE_EXPIRED);
			}
			// A capability can be used again until it expires. Freeze the first matching version,
			// and never overwrite an image already referenced by a profile or older message.
			String sourceTag = source.eTag().replace("\"", "");
			try {
				s3.copyObject(CopyObjectRequest.builder().destinationBucket(bucket).destinationKey(destination)
					.sourceBucket(bucket).sourceKey(key).copySourceIfMatch(source.eTag())
					.overrideConfiguration(config -> config.putHeader("If-None-Match", "*"))
					.metadataDirective(MetadataDirective.REPLACE).contentType(contentType)
					.metadata(Map.of("upload-etag", sourceTag)).build());
			} catch (S3Exception conflict) {
				if (conflict.statusCode() != 412) throw conflict;
				HeadObjectResponse alreadyAttached = head(destination);
				if (!sourceTag.equals(alreadyAttached.metadata().get("upload-etag"))) throw invalidImage();
			}
			return baseUrl + "/" + destination;
		} catch (S3Exception e) {
			if (e.statusCode() == 404) throw new BusinessException(GeneralErrorCode.FILE_EXPIRED);
			log.warn("Image storage refused promotion, status={}", e.statusCode());
			throw unavailable();
		} catch (SdkException e) {
			log.warn("Image storage unavailable during promotion", e);
			throw unavailable();
		}
	}

	private HeadObjectResponse head(String key) {
		return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
	}

	/** The temporary object also identifies an interrupted DB/Mongo promotion for later reconciliation. */
	public void cleanupExpired(Purpose purpose, BiPredicate<String, String> isReferenced) {
		cleanupBefore(purpose, Instant.now().minus(CLEANUP_AGE), isReferenced);
	}

	void cleanupBefore(Purpose purpose, Instant cutoff, BiPredicate<String, String> isReferenced) {
		String prefix = TEMPORARY + purpose.directory + "/";
		var pages = s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
		for (var object : pages.contents()) {
			if (!object.lastModified().isBefore(cutoff)) continue;
			String key = object.key();
			String[] parts = key.substring(prefix.length()).split("/", -1);
			boolean valid = purpose == Purpose.PROFILE
				? parts.length == 2 && MEMBER.matcher(parts[0]).matches()
				: parts.length == 3 && ROOM.matcher(parts[0]).matches() && MEMBER.matcher(parts[1]).matches();
			if (!valid || !OBJECT.matcher(parts[parts.length - 1]).matches()) continue;
			String destination = key.substring(TEMPORARY.length());
			try {
				// Keep the marker on any reference lookup or deletion failure so the next run retries.
				if (!isReferenced.test(parts[0], "/" + destination)) deleteFile(destination);
				deleteFile(key);
			} catch (RuntimeException e) {
				log.warn("Could not reconcile expired {} upload", purpose, e);
			}
		}
	}

	private void deleteFile(String key) {
		s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
	}

	private static String chatDirectory(long memberId, String roomId) {
		if (roomId == null || !ROOM.matcher(roomId).matches()) throw invalidImage();
		return "chat/" + roomId + "/" + memberId + "/";
	}

	private static String extension(String filename) {
		if (filename == null || filename.length() > 255) throw invalidImage();
		int dot = filename.lastIndexOf('.');
		String extension = dot > 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
		if (!TYPES.containsKey(extension)) throw invalidImage();
		return extension;
	}

	private static BusinessException invalidImage() {
		return new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "사용할 수 없는 이미지입니다.");
	}

	private static BusinessException unavailable() {
		return new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
	}
}
