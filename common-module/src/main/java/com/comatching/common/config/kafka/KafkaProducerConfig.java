package com.comatching.common.config.kafka;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
public class KafkaProducerConfig {

	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	private final ObjectProvider<MeterRegistry> meterRegistryProvider;

	public KafkaProducerConfig(ObjectProvider<MeterRegistry> meterRegistryProvider) {
		this.meterRegistryProvider = meterRegistryProvider;
	}

	private <K, V> DefaultKafkaProducerFactory<K, V> withMetrics(DefaultKafkaProducerFactory<K, V> factory) {
		meterRegistryProvider.ifAvailable(registry ->
				factory.addListener(new MicrometerProducerListener<>(registry)));
		return factory;
	}

	@Bean
	public ProducerFactory<String, String> producerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return withMetrics(new DefaultKafkaProducerFactory<>(config));
	}

	@Bean
	public KafkaTemplate<String, String> kafkaTemplate() {
		return new KafkaTemplate<>(producerFactory());
	}

	@Bean
	public StringJsonMessageConverter jsonConverter() {
		return new StringJsonMessageConverter();
	}

	@Bean
	public ProducerFactory<String, Object> jsonProducerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
		return withMetrics(new DefaultKafkaProducerFactory<>(config));
	}

	@Bean
	public KafkaTemplate<String, Object> jsonKafkaTemplate() {
		return new KafkaTemplate<>(jsonProducerFactory());
	}

	/**
	 * DLT 발행 전용.
	 *
	 * 역직렬화가 실패한 레코드는 값이 POJO 가 아니라 원본 바이트로 남는다.
	 * 그걸 JsonSerializer 로 내보내면 base64 문자열이 되어, 정작 DLT 를 열어
	 * 봐야 할 사람이 원문을 읽지 못한다. 깨진 메시지 보관이 DLT 의 주 용도이므로
	 * 바이트를 바이트 그대로 싣는 경로를 따로 둔다.
	 * DeadLetterPublishingRecoverer 가 값 타입을 보고 이 템플릿을 고른다.
	 */
	@Bean
	public ProducerFactory<String, byte[]> bytesProducerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
		return withMetrics(new DefaultKafkaProducerFactory<>(config));
	}

	@Bean
	public KafkaTemplate<String, byte[]> bytesKafkaTemplate() {
		return new KafkaTemplate<>(bytesProducerFactory());
	}
}
