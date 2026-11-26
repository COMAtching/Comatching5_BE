package com.comatching.common.service;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import com.comatching.common.dto.s3.S3UploadResponseDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Authenticated disposable S3-compatible storage validates the actual multipart bytes and policy signature. */
@Tag("integration")
class ImageUploadStorageIT {
	private static final String BUCKET = "fixture-images";
	private static final String IAM = """
		{"identities":[{"name":"fixture","credentials":[{"accessKey":"image-fixture","secretKey":"test-image-secret"}],
		"actions":["Admin","Read","Write","List","Tagging"]}]}
		""";

	@Test
	void storageEnforcesBothLimitsAndIssuerPromotesOnlyTheOwnedImmutableObject() throws Exception {
		try (var server = new GenericContainer<>("chrislusf/seaweedfs:4.54").withExposedPorts(8333)
				.withCopyToContainer(Transferable.of(IAM), "/tmp/s3-iam.json")
				.withCommand("mini", "-dir=/data", "-s3.port=8333", "-s3.config=/tmp/s3-iam.json", "-volume.max=4")
				.waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403))
				.withStartupTimeout(Duration.ofMinutes(2));
			var cache = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
			server.start();
			cache.start();
			URI endpoint = URI.create("http://" + server.getHost() + ":" + server.getMappedPort(8333));
			var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("image-fixture", "test-image-secret"));
			var factory = new LettuceConnectionFactory(cache.getHost(), cache.getMappedPort(6379));
			factory.afterPropertiesSet();
			try (var s3 = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
					.credentialsProvider(credentials)
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
				s3.createBucket(r -> r.bucket(BUCKET));
				String baseUrl = endpoint + "/" + BUCKET;
				var images = new S3Service(s3, new S3PostPolicy(credentials, new ObjectMapper()),
					new StringRedisTemplate(factory), BUCKET, "us-east-1", baseUrl, 4);
				var profile = images.issueProfile(11, "profile.png");
				var chat = images.issueChat(11, "room-2", "chat.jpg");
				for (var form : new S3UploadResponseDto[]{profile, chat}) {
					assertThat(post(form, Math.toIntExact(form.maxBytes()), (byte) 3)).isBetween(200, 299);
					assertThat(s3.headObject(r -> r.bucket(BUCKET).key(form.imageKey())).contentLength()).isEqualTo(form.maxBytes());
				}
				var largeProfile = images.issueProfile(11, "profile.png");
				var largeChat = images.issueChat(11, "room-2", "chat.jpg");
				for (var form : new S3UploadResponseDto[]{largeProfile, largeChat}) {
					assertThat(post(form, Math.toIntExact(form.maxBytes() + 1), (byte) 4)).isBetween(400, 499);
					assertThatThrownBy(() -> s3.headObject(r -> r.bucket(BUCKET).key(form.imageKey())))
						.isInstanceOfSatisfying(S3Exception.class, ex -> assertThat(ex.statusCode()).isEqualTo(404));
				}
				assertThatThrownBy(() -> images.issueProfile(11, "fifth.png")).isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo(GeneralErrorCode.DELIVERY_RATE_LIMITED));
				assertThatThrownBy(() -> images.attachChat(12, "room-2", chat.imageKey())).isInstanceOf(BusinessException.class);
				String finalKey = profile.imageKey().substring("pending-uploads/".length());
				assertThat(images.attachProfile(11, profile.imageKey())).isEqualTo(baseUrl + "/" + finalKey);
				assertThat(images.attachProfile(11, profile.imageKey())).isEqualTo(baseUrl + "/" + finalKey);
				assertThat(images.attachChat(11, "room-2", chat.imageKey())).startsWith(baseUrl + "/chat/room-2/11/");
				assertThat(post(profile, 5, (byte) 7)).isBetween(200, 299);
				assertThatThrownBy(() -> images.attachProfile(11, profile.imageKey())).isInstanceOf(BusinessException.class);
				assertThat(s3.headObject(r -> r.bucket(BUCKET).key(finalKey)).contentLength()).isEqualTo(profile.maxBytes());

				// Advance only the reconciliation cutoff. The real storage still performs every list and delete.
				Instant afterUploads = Instant.now().plusSeconds(10);
				images.cleanupBefore(S3Service.Purpose.PROFILE, afterUploads, (owner, path) ->
					owner.equals("11") && path.equals("/" + finalKey));
				images.cleanupBefore(S3Service.Purpose.CHAT, afterUploads, (room, path) -> false);
				assertThat(s3.listObjectsV2(r -> r.bucket(BUCKET)).contents()).extracting(obj -> obj.key())
					.containsExactly(finalKey);
			} finally {
				factory.destroy();
			}
		}
	}

	private static int post(S3UploadResponseDto form, int size, byte fill) throws Exception {
		String boundary = "upload-" + UUID.randomUUID();
		var prefix = new ByteArrayOutputStream();
		form.fields().forEach((key, value) -> prefix.writeBytes(("--" + boundary
			+ "\r\nContent-Disposition: form-data; name=\"" + key + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8)));
		prefix.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture\"\r\n"
			+ "Content-Type: " + form.fields().get("Content-Type") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		byte[] bytes = new byte[size];
		java.util.Arrays.fill(bytes, fill);
		var data = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix.toByteArray()),
			HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
		var request = HttpRequest.newBuilder(URI.create(form.uploadUrl())).timeout(Duration.ofSeconds(60))
			.header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(data).build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}
}
