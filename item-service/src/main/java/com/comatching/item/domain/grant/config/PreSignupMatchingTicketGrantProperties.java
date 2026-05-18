package com.comatching.item.domain.grant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "comatching.pre-signup.matching-ticket-grant")
public record PreSignupMatchingTicketGrantProperties(
	boolean enabled,
	@Positive int quantity,
	@NotBlank String campaignCode
) {
}
