package com.comatching.common.config.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

import java.time.Duration;
import java.util.List;

@Configuration
public class KafkaConsumerConfig {

    public static final String DLT_SUFFIX = ".DLT";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    // JSON 문자열을 리스너가 받는 이벤트 객체로 변환한다.
    @Bean
    public StringJsonMessageConverter jsonConverter() {
        return new StringJsonMessageConverter();
    }

    // DLT 재처리 기능
    @Bean
    @ConditionalOnProperty(name = "comatching.kafka.dlt-topics")
    public KafkaDltRedriveService kafkaDltRedriveService(
            KafkaOperations<String, Object> kafkaTemplate,
            @Value("${comatching.kafka.dlt-topics}") List<String> dltTopics
    ) {
        return new KafkaDltRedriveService(
                bootstrapServers,
                groupId,
                kafkaTemplate,
                dltTopics
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
                groupId,
                Duration.ofSeconds(cooldownSeconds)
        );
    }

    // 재시도 및 DLT 정책
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaOperations<String, Object> kafkaTemplate,
            KafkaDltAlertNotifier dltAlertNotifier
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1)
        );

        // 최초 처리 실패 후 1초, 2초, 4초 간격으로 3회 재시도
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(4_000L);

        ConsumerAwareRecordRecoverer alertingRecoverer =
                (record, consumer, exception) -> {
                    recoverer.accept(record, consumer, exception);
                    dltAlertNotifier.notifyParked(record, exception);
                };

        return new DefaultErrorHandler(alertingRecoverer, backOff);
    }

}
