package com.comatching.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import jakarta.persistence.EntityManagerFactory;

@Configuration
@EnableJpaAuditing
@ConditionalOnBean(EntityManagerFactory.class)
public class JpaAuditConfig {
}
