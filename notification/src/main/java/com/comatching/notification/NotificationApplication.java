package com.comatching.notification;

import com.comatching.common.config.kafka.KafkaConsumerConfig;
import com.comatching.common.config.kafka.KafkaDltRedriveController;
import com.comatching.common.config.kafka.KafkaDltTopicConfig;
import com.comatching.common.filter.InternalApiAuthenticationFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication(
        exclude = {
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@Import({
        KafkaConsumerConfig.class,
        KafkaDltTopicConfig.class,
        KafkaDltRedriveController.class,
        InternalApiAuthenticationFilter.class
})
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }

}
