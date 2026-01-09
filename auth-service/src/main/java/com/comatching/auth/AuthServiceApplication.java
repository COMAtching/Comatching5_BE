package com.comatching.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication(
	exclude = {
		DataSourceAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class
	}
)
@ComponentScan(basePackages = {"com.comatching.auth", "com.comatching.common"})
@EnableFeignClients(basePackages = "com.comatching.auth")
public class AuthServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthServiceApplication.class, args);
	}

}
