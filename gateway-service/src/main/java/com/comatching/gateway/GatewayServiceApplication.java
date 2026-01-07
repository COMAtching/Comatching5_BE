package com.comatching.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import com.comatching.common.exception.handler.GlobalExceptionHandler;

@SpringBootApplication(
	exclude = {
		DataSourceAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class
	}
)
@ComponentScan(
	basePackages = {
		"com.comatching.gateway",
		"com.comatching.common.util",
		"com.comatching.common.dto",
		"com.comatching.common.exception"
	},
	excludeFilters = {
		@ComponentScan.Filter(
			type = FilterType.ASSIGNABLE_TYPE,
			classes = GlobalExceptionHandler.class
		)
	}
)
public class GatewayServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayServiceApplication.class, args);
	}

}
