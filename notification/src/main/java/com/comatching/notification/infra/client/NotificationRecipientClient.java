package com.comatching.notification.infra.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.comatching.notification.domain.delivery.DeliveryNotSentException;

@Component
public class NotificationRecipientClient {
    private final RestClient users;

    public NotificationRecipientClient(RestClient.Builder builder, @Value("${user-service.url}") String baseUrl,
                                       @Value("${internal.service-token}") String token) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        users = builder.baseUrl(baseUrl).requestFactory(factory).defaultHeader("X-Internal-Token", token).build();
    }

    public Optional<String> findEmail(long memberId) {
        try {
            var response = users.get().uri("/api/internal/users/{id}/notification-email", memberId)
                    .accept(MediaType.TEXT_PLAIN).retrieve().toEntity(String.class);
            if (response.getStatusCode().equals(HttpStatus.NO_CONTENT)) return Optional.empty();
            if (!response.getStatusCode().equals(HttpStatus.OK) || !StringUtils.hasText(response.getBody())) {
                throw new DeliveryNotSentException("회원 메일 조회 응답 오류", null);
            }
            return Optional.of(response.getBody());
        } catch (RestClientException e) {
            throw new DeliveryNotSentException("회원 메일 주소 조회 실패", e);
        }
    }
}
