package com.comatching.notification.global.config;

import com.comatching.common.config.kafka.KafkaEventRoute;
import com.comatching.notification.domain.service.fcm.FcmDltException;
import com.comatching.notification.domain.delivery.DeliveryUncertainException;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOffExecution;

import java.util.concurrent.ThreadLocalRandom;

@Configuration
public class FcmKafkaErrorConfig {

    // 공통 에러 핸들러의 DLT 발행·알림 기능을 재사용한다.
    // 모든 일반 빈 생성 후, Kafka 리스너가 시작되기 전에 정책을 추가한다.
    @Bean
    public SmartInitializingSingleton fcmKafkaErrorPolicy(DefaultErrorHandler errorHandler) {
        return () -> {
            errorHandler.addNotRetryableExceptions(FcmDltException.class, DeliveryUncertainException.class);
            errorHandler.setBackOffFunction((record, exception) -> {
                boolean grantPush = KafkaEventRoute.ItemsGrantedGroups.PUSH.equals(KafkaUtils.getConsumerGroupId())
                        && KafkaEventRoute.ITEMS_GRANTED_PUSH.acceptsTopic(record.topic());
                if (!grantPush && !KafkaEventRoute.CHAT_NOTIFICATION.acceptsTopic(record.topic())) {
                    return null; // 다른 토픽은 공통 1·2·4초 정책 유지
                }

                ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
                backOff.setInitialInterval(10_000L);
                backOff.setMultiplier(2.0);
                backOff.setMaxInterval(40_000L);

                // 10·20·40초에 0~20%의 양수 지터를 더해 재시도 집중을 줄인다.
                return () -> {
                    BackOffExecution execution = backOff.start();
                    return () -> {
                        long delay = execution.nextBackOff();
                        if (delay == BackOffExecution.STOP) {
                            return BackOffExecution.STOP;
                        }
                        return delay + ThreadLocalRandom.current().nextLong(delay / 5 + 1);
                    };
                };
            });
        };
    }
}
