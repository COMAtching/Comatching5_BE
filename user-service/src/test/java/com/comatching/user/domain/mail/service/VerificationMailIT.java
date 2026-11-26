package com.comatching.user.domain.mail.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.testcontainers.containers.GenericContainer;
import org.thymeleaf.context.IContext;
import org.thymeleaf.spring6.SpringTemplateEngine;

import com.comatching.common.exception.BusinessException;
import com.comatching.user.domain.mail.enums.EmailType;
import com.comatching.user.infra.controller.EmailController;
import com.comatching.user.infra.controller.MailRequestSource;
import com.comatching.user.infra.controller.VerificationMailAdvice;
import com.comatching.user.global.security.cookie.AuthCookieFactory;

@Tag("integration")
class VerificationMailIT {
	static final GenericContainer<?> SERVER = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	static LettuceConnectionFactory connection;
	static StringRedisTemplate redis;

	@BeforeAll static void connect() {
		SERVER.start();
		connection = new LettuceConnectionFactory(SERVER.getHost(), SERVER.getMappedPort(6379));
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
	}

	@AfterAll static void close() {
		if (connection != null) connection.destroy();
		SERVER.stop();
	}

	private final List<String> attempts = new CopyOnWriteArrayList<>();
	private final JavaMailSender smtp = mock(JavaMailSender.class);
	private EmailService emails;

	@BeforeEach void setup() {
		try (var client = connection.getConnection()) { client.serverCommands().flushDb(); }
		var templates = mock(SpringTemplateEngine.class);
		when(templates.process(anyString(), any(IContext.class))).thenAnswer(invocation -> {
			IContext context = invocation.getArgument(1);
			attempts.add((String)context.getVariable("code"));
			return "<html>인증 메일</html>";
		});
		when(smtp.createMimeMessage()).thenAnswer(ignored -> new MimeMessage(Session.getInstance(new Properties())));
		emails = new EmailServiceImpl(new VerificationMailStore(redis, 6, 120, 120), smtp, redis, templates);
	}

	@Test void onlyAnAcceptedResendReplacesTheCodeAndResetsItsFiveMinuteLifetime() {
		String address = "member@example.invalid";
		emails.sendAuthCode(address, "one");
		String original = attempts.get(0);
		String active = key(EmailType.SIGNUP, "active");
		String delivery = key(EmailType.SIGNUP, "delivery");
		redis.expire(active, Duration.ofSeconds(70));
		assertThatThrownBy(() -> emails.sendAuthCode(address, "two")).isInstanceOfSatisfying(
			VerificationMailLimitException.class, e -> assertThat(e.getRetryAfterSeconds()).isBetween(59L, 60L));
		assertThat(attempts).hasSize(1);
		assertThat(redis.getExpire(active)).isBetween(67L, 70L);
		assertThat(redis.opsForHash().get(active, "code")).isEqualTo(original);

		// Advance the cooldown without sleeping in real time.
		redis.expire(delivery, Duration.ZERO);
		doThrow(new MailSendException("fixture SMTP unavailable")).doNothing().when(smtp).send(any(MimeMessage.class));
		assertThatThrownBy(() -> emails.sendAuthCode(address, "one")).isInstanceOf(BusinessException.class);
		assertThat(redis.opsForHash().get(active, "code")).isEqualTo(original);
		assertThat(redis.getExpire(active)).isBetween(66L, 70L);
		assertThat(redis.hasKey(delivery)).isFalse();

		emails.sendAuthCode(address, "one");
		assertThat(attempts.get(2)).isNotEqualTo(original);
		assertThat(redis.getExpire(active)).isBetween(298L, 300L);
		assertThatThrownBy(() -> emails.verifySignupCode(address, original)).isInstanceOf(BusinessException.class);
		assertThat(emails.verifySignupCode(address, attempts.get(2))).isNotBlank();
		assertThatThrownBy(() -> emails.verifySignupCode(address, attempts.get(2))).isInstanceOf(BusinessException.class);
	}

	@Test void concurrentRequestsForOneAddressSendOnceAndCannotReviveALegacyCode() throws Exception {
		String address = "shared@example.invalid";
		redis.opsForValue().set(EmailType.SIGNUP.getPrefix()+address, "LEGACY", Duration.ofMinutes(5));
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		Callable<Boolean> send = () -> {
			start.await();
			try { emails.sendAuthCode(address, "device"); return true; }
			catch (VerificationMailLimitException limited) { return false; }
		};
		try {
			var a = pool.submit(send);
			var b = pool.submit(send);
			start.countDown();
			assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
		} finally { pool.shutdownNow(); }
		assertThat(attempts).hasSize(1);
		assertThatThrownBy(() -> emails.verifySignupCode(address.toUpperCase(java.util.Locale.ROOT), attempts.get(0)))
			.isInstanceOf(BusinessException.class);
		assertThat(emails.verifySignupCode(address, attempts.get(0))).isNotEmpty();
		assertThatThrownBy(() -> emails.verifySignupCode(address, "LEGACY")).isInstanceOf(BusinessException.class);
	}

	@Test void signupHttpRouteReturnsTheActiveCooldown() throws Exception {
		var controller = new EmailController(emails,
			new MailRequestSource(""),
			new AuthCookieFactory(false, "", "Lax"));
		var mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new VerificationMailAdvice()).build();
		var body = "{\"email\":\"browser@example.invalid\"}";
		var http = post("/api/auth/email/send")
			.contentType("application/json").content(body);
		mvc.perform(http).andExpect(status().isOk());
		var resp = mvc.perform(http).andReturn().getResponse();
		assertThat(resp.getStatus()).isEqualTo(429);
		assertThat(Long.parseLong(resp.getHeader("Retry-After"))).isBetween(59L, 60L);
		assertThat(attempts).hasSize(1);
	}

	@Test void recipientSourceAndGlobalBudgetsCoverBothMailPurposes() {
		var store = new VerificationMailStore(redis, 1, 1, 2);
		store.reserve(EmailType.SIGNUP, "a@example.invalid", "one");
		assertThatThrownBy(() -> store.reserve(EmailType.PASSWORD_RESET, "A@example.invalid", "two"))
			.isInstanceOfSatisfying(VerificationMailLimitException.class, e -> assertThat(e.getRetryAfterSeconds()).isGreaterThan(3500));
		assertThatThrownBy(() -> store.reserve(EmailType.PASSWORD_RESET, "b@example.invalid", "one"))
			.isInstanceOfSatisfying(VerificationMailLimitException.class, e -> assertThat(e.getRetryAfterSeconds()).isGreaterThan(590));
		store.reserve(EmailType.PASSWORD_RESET, "b@example.invalid", "two");
		assertThatThrownBy(() -> store.reserve(EmailType.SIGNUP, "c@example.invalid", "three"))
			.isInstanceOfSatisfying(VerificationMailLimitException.class, e -> assertThat(e.getRetryAfterSeconds()).isBetween(59L, 60L));
	}

	private static String key(EmailType purpose, String suffix) {
		return redis.keys("auth:mail:{limits}:" + purpose.name() + ":*:" + suffix).iterator().next();
	}
}
