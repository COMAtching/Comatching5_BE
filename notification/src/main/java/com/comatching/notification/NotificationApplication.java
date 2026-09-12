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
import com.comatching.common.config.kafka.KafkaDltRedriveController;
import com.comatching.common.config.kafka.KafkaDltTopicConfig;
import com.comatching.common.config.kafka.KafkaProducerConfig;
import com.comatching.common.filter.InternalApiAuthenticationFilter;

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
	KafkaDltTopicConfig.class,
	// DLT 재적재 트리거와 그 인증 필터. 이 서비스는 common 을 컴포넌트
	// 스캔하지 않아 여기 안 적으면 빈이 안 생긴다. 컨트롤러만 넣고 필터를
	// 빠뜨리면 /api/internal/** 이 무인증으로 열리므로(SecurityConfig 는
	// 해당 경로를 permitAll 로 두고 필터에 위임) 반드시 둘을 같이 등록한다.
	KafkaDltRedriveController.class,
	InternalApiAuthenticationFilter.class
})
public class NotificationApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationApplication.class, args);
	}

}
