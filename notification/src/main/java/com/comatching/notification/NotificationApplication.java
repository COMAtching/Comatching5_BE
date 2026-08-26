package com.comatching.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

import com.comatching.common.config.S3Config;
import com.comatching.common.config.kafka.KafkaConsumerConfig;
import com.comatching.common.config.kafka.KafkaDltTopicConfig;
import com.comatching.common.config.kafka.KafkaProducerConfig;

@SpringBootApplication(
	exclude = {
		DataSourceAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class,
		UserDetailsServiceAutoConfiguration.class
	}
)
@Import({
	KafkaConsumerConfig.class,
	// 이 서비스는 발행을 하지 않지만 DLT 발행에는 프로듀서가 필요하다.
	// 없으면 재시도를 다 쓴 메시지를 옮길 수단이 없어 컨텍스트가 뜨지 않는다.
	KafkaProducerConfig.class,
	KafkaDltTopicConfig.class
})
public class NotificationApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationApplication.class, args);
	}

}
