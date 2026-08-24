package com.comatching.common.config.kafka;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.kafka.support.serializer.JsonDeserializer;

@Configuration
public class KafkaConsumerConfig {

	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	@Value("${spring.kafka.consumer.group-id}")
	private String groupId;

	private final ObjectProvider<MeterRegistry> meterRegistryProvider;

	public KafkaConsumerConfig(ObjectProvider<MeterRegistry> meterRegistryProvider) {
		this.meterRegistryProvider = meterRegistryProvider;
	}

	private <K, V> DefaultKafkaConsumerFactory<K, V> withMetrics(DefaultKafkaConsumerFactory<K, V> factory) {
		meterRegistryProvider.ifAvailable(registry ->
				factory.addListener(new MicrometerConsumerListener<>(registry)));

		return factory;
	}

	// 이 팩토리는 yml 의 spring.kafka.consumer.* 를 읽지 않으므로(수동 구성)
	// auto.offset.reset 을 명시하지 않으면 클라이언트 기본값 latest 가 적용된다.
	// latest 는 파티션 증설과 만나면 유실이 된다: 새 파티션에는 커밋 오프셋이
	// 없어서, 컨슈머 그룹이 그 파티션을 처음 배정받기 전에 발행된 메시지를
	// 로그 끝으로 건너뛴다(탈퇴 메일 누락, 후보 tombstone 미기록). earliest 는
	// 커밋 오프셋이 있는 기존 파티션에는 영향이 없고 새 파티션만 처음부터 읽는다.
	private static final String AUTO_OFFSET_RESET = "earliest";

	@Bean
	public ConsumerFactory<String, String> consumerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, AUTO_OFFSET_RESET);
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		return withMetrics(new DefaultKafkaConsumerFactory<>(config));
	}

	@Bean
	public ConsumerFactory<String, Object> jsonConsumerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, AUTO_OFFSET_RESET);
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
		config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.comatching.common.dto.event.*");
		return withMetrics(new DefaultKafkaConsumerFactory<>(config));
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
		ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory());

		factory.setRecordMessageConverter(new StringJsonMessageConverter());

		return factory;
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> jsonKafkaListenerContainerFactory() {
		ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(jsonConsumerFactory());
		return factory;
	}

}
