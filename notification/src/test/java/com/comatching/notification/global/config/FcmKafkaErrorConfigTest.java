package com.comatching.notification.global.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import com.comatching.common.config.kafka.KafkaEventRoute;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

class FcmKafkaErrorConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {"chat-message-sent", KafkaEventRoute.ReplayTopics.CHAT_NOTIFICATION})
    @DisplayName("FCM 대기는 10~12·20~24·40~48초이고 세 번 뒤 종료한다")
    void boundsJitterAndStopsAfterThreeRetries(String topic) {
        var policy = configuredPolicy();
        for (int sample = 0; sample < 32; sample++) {
            BackOffExecution execution = policy.apply(record(topic), new IllegalStateException()).start();
            assertThat(execution.nextBackOff()).isBetween(10_000L, 12_000L);
            assertThat(execution.nextBackOff()).isBetween(20_000L, 24_000L);
            assertThat(execution.nextBackOff()).isBetween(40_000L, 48_000L);
            assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
        }
    }

    @Test
    @DisplayName("서로 다른 실행은 재시도 횟수를 공유하지 않는다")
    void executionsHaveIndependentProgress() {
        BackOff plan = configuredPolicy().apply(record("chat-message-sent"), new IllegalStateException());
        var first = plan.start();
        first.nextBackOff();
        first.nextBackOff();
        assertThat(plan.start().nextBackOff()).isBetween(10_000L, 12_000L);
        assertThat(first.nextBackOff()).isBetween(40_000L, 48_000L);
    }

    @Test
    @DisplayName("메일 토픽은 공통 1·2·4초 정책으로 돌아간다")
    void otherTopicsKeepDefaultPolicy() {
        assertThat(configuredPolicy().apply(record("member-withdraw"), new IllegalStateException())).isNull();
    }

    @Test
    void onlyPushGroupUsesFcmBackoffOnSharedGrantTopic() {
        var policy = configuredPolicy();
        try {
            KafkaUtils.setConsumerGroupId(KafkaEventRoute.ItemsGrantedGroups.MAIL);
            assertThat(policy.apply(record("items-granted"), new IllegalStateException())).isNull();
            KafkaUtils.setConsumerGroupId(KafkaEventRoute.ItemsGrantedGroups.PUSH);
            for (String topic : java.util.List.of("items-granted", KafkaEventRoute.ReplayTopics.ITEMS_GRANTED_PUSH)) {
                assertThat(policy.apply(record(topic), new IllegalStateException()).start().nextBackOff())
                        .isBetween(10_000L, 12_000L);
            }
        } finally {
            KafkaUtils.clearConsumerGroupId();
        }
    }

    private BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> configuredPolicy() {
        DefaultErrorHandler handler = mock(DefaultErrorHandler.class);
        AtomicReference<BiFunction<ConsumerRecord<?, ?>, Exception, BackOff>> selected = new AtomicReference<>();
        doAnswer(invocation -> {
            selected.set(invocation.getArgument(0));
            return null;
        }).when(handler).setBackOffFunction(any());
        new FcmKafkaErrorConfig().fcmKafkaErrorPolicy(handler).afterSingletonsInstantiated();
        return selected.get();
    }

    private ConsumerRecord<String, String> record(String topic) {
        return new ConsumerRecord<>(topic, 0, 42L, "room-1", "{}");
    }
}
