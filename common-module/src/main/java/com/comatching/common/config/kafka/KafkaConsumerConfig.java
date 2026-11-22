package com.comatching.common.config.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    public static final String DLT_SUFFIX = ".DLT";

    // JSON 문자열을 리스너가 받는 이벤트 객체로 변환한다.
    @Bean
    public StringJsonMessageConverter jsonConverter() {
        return new StringJsonMessageConverter();
    }

    // DLT 재처리 기능
    @Bean
    @ConditionalOnProperty(name = "comatching.kafka.dlt-routes")
    public KafkaDltRedriveService kafkaDltRedriveService(
            ConsumerFactory<?, ?> consumerFactory,
            KafkaOperations<String, Object> kafkaTemplate,
            @Value("${comatching.kafka.dlt-routes}") List<KafkaEventRoute> routes
    ) {
        return new KafkaDltRedriveService(
                consumerFactory.getConfigurationProperties(),
                kafkaTemplate,
                routes
        );
    }

    // DLT 적재 알림 기능
    @Bean
    public KafkaDltAlertNotifier kafkaDltAlertNotifier(
            @Value("${comatching.kafka.dlt-alert-webhook-url:}") String webhookUrl,
            @Value("${comatching.kafka.dlt-alert-cooldown-seconds:300}") long cooldownSeconds
    ) {
        return new KafkaDltAlertNotifier(
                webhookUrl,
                Duration.ofSeconds(cooldownSeconds)
        );
    }

    // 재시도 및 DLT 정책
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaOperations<String, Object> kafkaTemplate,
            KafkaDltAlertNotifier notifier
    ) {
        Map<KafkaEventRoute, DeadLetterPublishingRecoverer> recoverers = new EnumMap<>(KafkaEventRoute.class);

        for (KafkaEventRoute route : KafkaEventRoute.values()) {
            var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                    (record, exception) -> new TopicPartition(route.dltTopic(), -1));
            // DLT 전송 실패를 정상 복구로 간주하지 않는다.
            recoverer.setFailIfSendResultIsError(true);
            recoverers.put(route, recoverer);
        }

        ConsumerAwareRecordRecoverer routingRecoverer = (record, consumer, exception) -> {
            // YAML 기본 그룹이 아니라 이 레코드를 실제로 처리한 Consumer의 그룹이다.
            KafkaEventRoute route = KafkaEventRoute.forRecord(record.topic(), consumer.groupMetadata().groupId());
            recoverers.get(route).accept(record, consumer, exception);
            notifier.notifyParked(record, exception, route);
        };

        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(4_000L);
        return new DefaultErrorHandler(routingRecoverer, backOff);
    }

}
