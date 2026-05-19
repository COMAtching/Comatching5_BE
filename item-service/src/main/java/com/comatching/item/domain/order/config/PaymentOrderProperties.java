package com.comatching.item.domain.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "comatching.order")
public record PaymentOrderProperties(
	@Positive long expireMinutes
) {
}
