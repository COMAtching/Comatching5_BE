package com.comatching.notification.infra.client;

import java.net.http.HttpClient;
import java.time.Duration;

import com.comatching.notification.domain.delivery.DeliveryNotSentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ChatPushEligibilityClient {
    private final RestClient chat;

    public ChatPushEligibilityClient(RestClient.Builder builder, @Value("${chat-service.url}") String baseUrl,
                                     @Value("${internal.service-token}") String token) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        chat = builder.baseUrl(baseUrl).requestFactory(factory).defaultHeader("X-Internal-Token", token).build();
    }

    public boolean isEligible(String messageId, long targetUserId) {
        try {
            var response = chat.get()
                    .uri("/api/internal/chat/messages/{id}/push-eligible?targetUserId={target}", messageId, targetUserId)
                    .retrieve().toEntity(Boolean.class);
            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new DeliveryNotSentException("채팅 푸시 대상 조회 응답 오류", null);
            }
            return response.getBody();
        } catch (RestClientException e) {
            throw new DeliveryNotSentException("채팅 푸시 대상 조회 실패", e);
        }
    }
}
