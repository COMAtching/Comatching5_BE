package com.comatching.common.config.kafka;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
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

	public static final String DLT_SUFFIX = ".DLT";

	// 재시도 4회(최초 1 + 재시도 3), 간격 1초 → 2초 → 4초. 총 7초.
	private static final int RETRY_MAX_RETRIES = 3;
	private static final long RETRY_INITIAL_INTERVAL_MS = 1_000L;
	private static final double RETRY_MULTIPLIER = 2.0;
	private static final long RETRY_MAX_INTERVAL_MS = 4_000L;

	/**
	 * 재시도와 DLT 정책을 한 곳에 둔다. 컨슈머마다 예외를 삼키거나 재던지는
	 * 방침이 달라서 실패했을 때의 결과가 제각각이었던 것을 없애기 위함이다.
	 *
	 * == 왜 비차단(@RetryableTopic) 이 아니라 차단 재시도인가 ==
	 * 비차단 재시도는 실패한 레코드를 별도 재시도 토픽으로 보내고 원래 파티션은
	 * 곧바로 다음 레코드를 처리한다. 그러면 같은 회원의 뒤 이벤트가 앞 이벤트보다
	 * 먼저 반영되고, 나중에 재시도된 앞 이벤트가 그걸 덮어쓴다. 메시지 키로 만든
	 * 파티션 내 순서 보장이 재시도 경로에서만 무너지는 셈이다.
	 * 그 자리에서 멈추고 다시 시도하는 차단 방식은 순서를 지킨다.
	 *
	 * == 총 7초로 묶은 이유 ==
	 * 차단 재시도는 그동안 그 파티션을 멈춘다(head-of-line blocking).
	 * 재시도 총합이 max.poll.interval.ms(기본 5분)를 넘으면 브로커가 컨슈머를
	 * 죽은 것으로 보고 리밸런스를 일으켜 문제가 더 커지므로 크게 아래로 둔다.
	 *
	 * == 무엇을 재시도에서 빼는가 ==
	 * 역직렬화·타입 변환 실패는 DefaultErrorHandler 가 기본적으로 재시도 대상에서
	 * 빼고 곧장 DLT 로 보낸다. 같은 바이트를 다시 읽어도 결과가 같기 때문이다.
	 * 반면 업무 예외(BusinessException)는 일부러 재시도 대상으로 남겼다.
	 * Feign 호출 실패 같은 일시적 원인이 업무 예외로 감싸여 올라오는 경로가 있어서,
	 * 재시도 제외로 분류하면 회복할 수 있는 실패까지 DLT 로 보내게 된다.
	 */
	@Bean
	public DefaultErrorHandler kafkaErrorHandler(
		@Qualifier("kafkaTemplate") KafkaOperations<String, String> stringTemplate,
		@Qualifier("jsonKafkaTemplate") KafkaOperations<String, Object> jsonTemplate,
		@Qualifier("bytesKafkaTemplate") KafkaOperations<String, byte[]> bytesTemplate) {

		// 값 타입에 따라 템플릿을 고른다. 가장 구체적인 타입이 앞에 와야 하므로
		// 순서가 보존되는 맵을 쓰고 Object 를 마지막에 둔다.
		Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
		templates.put(byte[].class, bytesTemplate);   // 역직렬화 실패분(원본 바이트)
		templates.put(String.class, stringTemplate);  // String 경로
		templates.put(Object.class, jsonTemplate);    // JSON 경로

		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
			templates,
			// 원본 파티션 번호를 그대로 쓰지 않고 -1 을 넘긴다. 원본이 3파티션인데
			// DLT 가 1파티션이면 "2번 파티션으로 보내라"가 실패해서, 유실을 막으려고
			// 만든 장치가 유실 지점이 되기 때문이다. -1 이면 프로듀서가 키로 고른다.
			(record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));

		ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(RETRY_MAX_RETRIES);
		backOff.setInitialInterval(RETRY_INITIAL_INTERVAL_MS);
		backOff.setMultiplier(RETRY_MULTIPLIER);
		backOff.setMaxInterval(RETRY_MAX_INTERVAL_MS);

		return new DefaultErrorHandler(recoverer, backOff);
	}

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
		// JsonDeserializer 를 그대로 쓰면 역직렬화 실패가 poll 단계에서 터진다.
		// 그 단계에는 에러 핸들러가 개입할 수 없어서 오프셋이 넘어가지 못하고
		// 같은 레코드를 무한히 다시 읽는다(poison pill - 파티션이 영구히 막힘).
		// ErrorHandlingDeserializer 로 감싸면 실패가 리스너 단계의 예외로 바뀌어
		// DLT 로 보낼 수 있고, 뒤 메시지가 계속 흐른다.
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
		config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
		config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.comatching.common.dto.event.*");
		return withMetrics(new DefaultKafkaConsumerFactory<>(config));
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
		DefaultErrorHandler kafkaErrorHandler) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory());

		factory.setRecordMessageConverter(new StringJsonMessageConverter());
		factory.setCommonErrorHandler(kafkaErrorHandler);

		return factory;
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Object> jsonKafkaListenerContainerFactory(
		DefaultErrorHandler kafkaErrorHandler) {
		ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(jsonConsumerFactory());
		factory.setCommonErrorHandler(kafkaErrorHandler);
		return factory;
	}

}
