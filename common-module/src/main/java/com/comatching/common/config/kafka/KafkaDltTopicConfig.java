package com.comatching.common.config.kafka;

import java.util.List;
import java.util.stream.Stream;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
@ConditionalOnProperty(name = "comatching.kafka.dlt-routes")
public class KafkaDltTopicConfig {

    private final List<KafkaEventRoute> routes;
    private final short replicationFactor;

    public KafkaDltTopicConfig(
            @Value("${comatching.kafka.dlt-routes}") List<KafkaEventRoute> routes,
            @Value("${comatching.kafka.replication-factor:1}") short replicationFactor) {
        this.routes = List.copyOf(routes);
        this.replicationFactor = replicationFactor;
    }

    @Bean
    public KafkaAdmin.NewTopics deadLetterTopics() {
        // 수동 재처리용 저용량 경로이므로 DLT·replay는 각각 1파티션으로 시작한다.
        NewTopic[] topics = routes.stream()
                .flatMap(route -> Stream.of(route.dltTopic(), route.replayTopic()))
                .distinct()
                .map(topic -> TopicBuilder.name(topic).partitions(1).replicas(replicationFactor).build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}
