package com.comatching.common.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/** AWS Signature V4 browser POST policy. Every client-supplied field has an exact-match condition. */
@Component
@RequiredArgsConstructor
public class S3PostPolicy {
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);
	private static final String ALGORITHM = "AWS4-HMAC-SHA256";

	private final AwsCredentialsProvider credentials;
	private final ObjectMapper json;

	Map<String, String> sign(String bucket, String region, String key, String type, long limit, Instant issuedAt) {
		var identity = credentials.resolveCredentials();
		String timestamp = TIMESTAMP.format(issuedAt);
		String date = timestamp.substring(0, 8);
		String scope = date + "/" + region + "/s3/aws4_request";
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("key", key);
		fields.put("Content-Type", type);
		fields.put("x-amz-algorithm", ALGORITHM);
		fields.put("x-amz-credential", identity.accessKeyId() + "/" + scope);
		fields.put("x-amz-date", timestamp);
		if (identity instanceof AwsSessionCredentials session) {
			fields.put("x-amz-security-token", session.sessionToken());
		}

		List<Object> conditions = new ArrayList<>();
		conditions.add(Map.of("bucket", bucket));
		fields.forEach((name, value) -> conditions.add(Map.of(name, value)));
		conditions.add(List.of("content-length-range", 1, limit));
		try {
			byte[] document = json.writeValueAsBytes(Map.of(
				"expiration", issuedAt.truncatedTo(ChronoUnit.SECONDS).plusSeconds(300).toString(), "conditions", conditions));
			String policy = Base64.getEncoder().encodeToString(document);
			byte[] dateKey = hmac(("AWS4" + identity.secretAccessKey()).getBytes(StandardCharsets.UTF_8), date);
			byte[] signingKey = hmac(hmac(hmac(dateKey, region), "s3"), "aws4_request");
			fields.put("policy", policy);
			fields.put("x-amz-signature", HexFormat.of().formatHex(hmac(signingKey, policy)));
			return Map.copyOf(fields);
		} catch (JsonProcessingException | GeneralSecurityException e) {
			throw new IllegalStateException("Cannot sign S3 upload policy", e);
		}
	}

	private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
	}
}
