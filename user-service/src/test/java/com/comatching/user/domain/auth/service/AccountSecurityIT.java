package com.comatching.user.domain.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.thymeleaf.spring6.SpringTemplateEngine;

import com.comatching.common.auth.LoginSession;
import com.comatching.common.auth.SessionRedisScripts;
import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.auth.MemberLoginDto;
import com.comatching.common.dto.auth.SignupRequest;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.member.ProfileCreateRequest;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.service.S3Service;
import com.comatching.common.util.JwtUtil;
import com.comatching.user.domain.auth.dto.ChangePasswordRequest;
import com.comatching.user.domain.auth.dto.ResetPasswordRequest;
import com.comatching.user.domain.auth.dto.TokenResponse;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.mail.enums.EmailType;
import com.comatching.user.domain.mail.service.EmailService;
import com.comatching.user.domain.mail.service.EmailServiceImpl;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.domain.member.service.MemberServiceImpl;
import com.comatching.user.domain.member.service.ProfileServiceImpl;
import com.comatching.user.global.config.ProfileImageProperties;
import com.comatching.user.global.security.cookie.AuthCookieFactory;
import com.comatching.user.global.security.handler.LoginSessionWriter;
import com.comatching.user.global.security.oauth2.handler.CustomOAuth2SuccessHandler;
import com.comatching.user.global.security.oauth2.provider.kakao.config.KakaoProperties;
import com.comatching.user.global.security.oauth2.provider.kakao.unlink.KakaoAuthClient;
import com.comatching.user.global.security.oauth2.user.CustomOAuth2User;
import com.comatching.user.support.OutboxMySqlSupport;
import com.fasterxml.jackson.databind.ObjectMapper;

@DataJpaTest(showSql = false, properties = {
	"spring.jpa.hibernate.ddl-auto=create-drop", "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
	"jwt.access-token.expiration=60000", "jwt.refresh-token.expiration=120000"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AccountSecurityIT.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("integration")
class AccountSecurityIT extends OutboxMySqlSupport {
	@Configuration
	@EntityScan(basePackageClasses = Member.class)
	@EnableJpaRepositories(basePackageClasses = MemberRepository.class)
	@Import({MemberServiceImpl.class, LoginSessionStore.class, LoginSessionService.class, JwtUtil.class,
		AuthServiceImpl.class, SignupServiceImpl.class, EmailServiceImpl.class, ProfileServiceImpl.class})
	static class Config {
		@Bean(destroyMethod = "stop")
		GenericContainer<?> redisServer() {
			GenericContainer<?> server = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
			server.start();
			return server;
		}

		@Bean
		LettuceConnectionFactory connectionFactory(GenericContainer<?> redisServer) {
			return new LettuceConnectionFactory(redisServer.getHost(), redisServer.getMappedPort(6379));
		}

		@Bean
		StringRedisTemplate redis(LettuceConnectionFactory factory) { return new StringRedisTemplate(factory); }

		@Bean
		PasswordEncoder passwords() { return new BCryptPasswordEncoder(4); }
	}

	@Autowired AuthService auth;
	@Autowired LoginSessionService sessions;
	@Autowired MemberRepository members;
	@Autowired StringRedisTemplate redis;
	@Autowired PasswordEncoder passwords;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtUtil jwt;
	@Autowired EmailService email;
	@Autowired SignupService signup;
	@MockitoSpyBean LoginSessionStore store;
	@MockitoBean UserEventPublisher events;
	@MockitoBean ProfileImageProperties images;
	@MockitoBean S3Service s3;
	@MockitoBean KakaoAuthClient kakao;
	@MockitoBean KakaoProperties kakaoProperties;
	@MockitoBean JavaMailSender mail;
	@MockitoBean SpringTemplateEngine templates;

	private Member member(MemberStatus status) {
		return members.saveAndFlush(Member.builder().email(UUID.randomUUID() + "@example.invalid")
			.password(passwords.encode("original")).role(MemberRole.ROLE_USER).status(status).build());
	}

	private boolean active(String accessToken) {
		LoginSession s = jwt.readAccess(accessToken).session();
		return isActive(redis, s);
	}

	private static boolean isActive(StringRedisTemplate database, LoginSession session) {
		return Long.valueOf(1).equals(database.execute(SessionRedisScripts.IS_ACTIVE,
			List.of(LoginSession.versionKey(session.memberId()), session.sessionKey()), Long.toString(session.authVersion())));
	}

	@Test
	void latestDeviceAloneRotatesAndEarlierLogoutCannotRemoveIt() {
		Member m = member(MemberStatus.ACTIVE);
		TokenResponse old = sessions.login(m.getId(), 0);
		TokenResponse current = sessions.login(m.getId(), 0);
		assertThat(active(old.accessToken())).isTrue();
		assertThatThrownBy(() -> auth.reissue(old.refreshToken())).isInstanceOf(BusinessException.class);
		auth.logout(old.accessToken(), old.refreshToken());
		assertThat(active(old.accessToken())).isFalse();
		TokenResponse rotated = auth.reissue(current.refreshToken());
		assertThat(active(rotated.accessToken())).isTrue();
		assertThatThrownBy(() -> auth.reissue(current.refreshToken())).isInstanceOf(BusinessException.class);
		auth.logout(null, rotated.refreshToken());
		assertThat(active(current.accessToken())).isFalse();
		assertThat(active(rotated.accessToken())).isFalse();
		assertThatThrownBy(() -> auth.reissue(rotated.refreshToken())).isInstanceOf(BusinessException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"change", "reset", "withdraw"})
	void accountChangeInvalidatesBothDevicesAndStaleLoginSnapshot(String operation) {
		Member m = member(MemberStatus.ACTIVE);
		TokenResponse first = sessions.login(m.getId(), 0);
		TokenResponse second = sessions.login(m.getId(), 0);
		switch (operation) {
			case "change" -> auth.changePassword(m.getId(), new ChangePasswordRequest("original", "replacement"));
			case "reset" -> {
				redis.opsForValue().set(EmailType.PASSWORD_RESET.getPrefix() + m.getEmail(), "ABC234");
				auth.resetPassword(new ResetPasswordRequest(m.getEmail(), "ABC234", "replacement"));
			}
			case "withdraw" -> auth.withdraw(m.getId());
			default -> throw new AssertionError(operation);
		}
		assertThat(members.findById(m.getId()).orElseThrow().getAuthVersion()).isOne();
		assertThatThrownBy(() -> sessions.login(m.getId(), 0)).isInstanceOf(BusinessException.class);
		if (!operation.equals("withdraw")) assertThat(active(sessions.login(m.getId(), 1).accessToken())).isTrue();
		else assertThatThrownBy(() -> sessions.login(m.getId(), 1)).isInstanceOf(BusinessException.class);
		for (var old : List.of(first, second)) {
			assertThat(active(old.accessToken())).isFalse();
			assertThatThrownBy(() -> auth.reissue(old.refreshToken())).isInstanceOf(BusinessException.class);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"BANNED", "SUSPENDED", "DORMANT"})
	void lockedAccountCannotRenewEvenWithTheCurrentRefreshToken(String status) {
		Member m = member(MemberStatus.ACTIVE);
		var tokens = sessions.login(m.getId(), 0);
		jdbc.update("update members set status = ? where member_id = ?", status, m.getId());
		assertThatThrownBy(() -> auth.reissue(tokens.refreshToken())).isInstanceOf(BusinessException.class);
	}

	@Test
	void guestOnboardingKeepsSessionAndIssuesCurrentRoleAndNickname() {
		Member guest = members.saveAndFlush(Member.builder().role(MemberRole.ROLE_GUEST).status(MemberStatus.ACTIVE)
			.email(UUID.randomUUID() + "@example.invalid").build());
		var superseded = sessions.login(guest.getId(), 0);
		var old = sessions.login(guest.getId(), 0);
		given(images.baseUrl()).willReturn("http://localhost/assets");
		var profile = ProfileCreateRequest.builder().nickname("새회원").gender(Gender.MALE)
			.birthDate(LocalDate.of(2000, 1, 1)).mbti("ISTJ").university("대학").major("전공")
			.contactFrequency(ContactFrequency.NORMAL)
			.hobbies(List.of(new HobbyDto(HobbyCategory.SPORTS, "축구"), new HobbyDto(HobbyCategory.CULTURE, "영화")))
			.tags(List.of()).build();
		var actor = new MemberInfo(guest.getId(), guest.getEmail(), "ROLE_GUEST");
		assertThatThrownBy(() -> signup.completeSignup(actor, profile, superseded.accessToken()))
			.isInstanceOf(BusinessException.class);
		assertThat(members.findById(guest.getId()).orElseThrow().getRole()).isEqualTo(MemberRole.ROLE_GUEST);
		var completed = signup.completeSignup(actor, profile, old.accessToken());
		var renewed = jwt.readAccess(completed.tokens().accessToken());
		assertThat(renewed.session()).isEqualTo(jwt.readAccess(old.accessToken()).session());
		assertThat(renewed.claims().get("role")).isEqualTo("ROLE_USER");
		assertThat(renewed.claims().get("nickname")).isEqualTo("새회원");
		assertThatThrownBy(() -> auth.reissue(old.refreshToken())).isInstanceOf(BusinessException.class);
		assertThat(active(auth.reissue(completed.tokens().refreshToken()).accessToken())).isTrue();
	}

	@Test
	void kakaoSuccessUsesAuthenticatedVersionAndDoesNotSetCookiesAfterRevocation() throws Exception {
		Member m = member(MemberStatus.ACTIVE);
		var principal = new CustomOAuth2User(MemberLoginDto.builder().id(m.getId()).email(m.getEmail())
			.authVersion(0).role("ROLE_USER").status("ACTIVE").build(), Map.of());
		var handler = new CustomOAuth2SuccessHandler(new LoginSessionWriter(sessions,
			new AuthCookieFactory(false, "", "Lax"), new ObjectMapper()));
		ReflectionTestUtils.setField(handler, "clientUrl", "http://localhost");
		var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		var response = new MockHttpServletResponse();
		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
		assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/main");
		assertThat(response.getHeaders("Set-Cookie")).hasSize(2);
		auth.changePassword(m.getId(), new ChangePasswordRequest("original", "replacement"));
		var staleResponse = new MockHttpServletResponse();
		handler.onAuthenticationSuccess(new MockHttpServletRequest(), staleResponse, authentication);
		assertThat(staleResponse.getStatus()).isEqualTo(401);
		assertThat(staleResponse.getHeaders("Set-Cookie")).isEmpty();
	}

	@Test
	void anExpiredProofCannotCreateAMember() {
		String address = UUID.randomUUID() + "@example.invalid";
		redis.opsForValue().set(EmailType.SIGNUP.getPrefix() + address, "CODE23");
		String proof = email.verifySignupCode(address, "CODE23");
		String key = redis.keys("auth:signup-proof:*").stream()
			.filter(k -> address.equals(redis.opsForValue().get(k))).findFirst().orElseThrow();
		assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1795L, 1800L);
		redis.expire(key, Duration.ZERO);
		assertThatThrownBy(() -> signup.signup(new SignupRequest(address, "Password!2"), proof))
			.isInstanceOf(BusinessException.class);
		assertThat(members.findByEmail(address)).isEmpty();
	}

	@Test
	void revocationFailureDoesNotCommitPasswordChange() {
		Member m = member(MemberStatus.ACTIVE);
		willThrow(new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE)).given(store).endAll(m.getId());
		assertThatThrownBy(() -> auth.changePassword(m.getId(), new ChangePasswordRequest("original", "new")))
			.isInstanceOf(BusinessException.class);
		Member persisted = members.findById(m.getId()).orElseThrow();
		assertThat(persisted.getAuthVersion()).isZero();
		assertThat(passwords.matches("original", persisted.getPassword())).isTrue();
	}

	@Test
	void redisSnapshotRestoreAndPrimaryRepromotionCannotReviveOldSessions() throws Exception {
		Member m = member(MemberStatus.ACTIVE);
		TokenResponse tokens = sessions.login(m.getId(), 0);
		var old = jwt.readRefresh(tokens.refreshToken());
		LoginSession session = old.session();
		var keys = List.of(LoginSession.versionKey(m.getId()), session.sessionKey(), LoginSession.refreshKey(m.getId()));
		List<String> beforeLogout = redis.opsForValue().multiGet(keys);
		auth.logout(tokens.accessToken(), tokens.refreshToken());

		try (var replacement = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
			replacement.start();
			var connection = new LettuceConnectionFactory(replacement.getHost(), replacement.getMappedPort(6379));
			connection.afterPropertiesSet();
			try {
				var restored = new StringRedisTemplate(connection);
				for (int i = 0; i < keys.size(); i++) restored.opsForValue().set(keys.get(i), beforeLogout.get(i));
				assertThat(isActive(restored, session)).isFalse();
				var restoredStore = new LoginSessionStore(restored, 60000, 120000);
				assertThatThrownBy(() -> restoredStore.rotate(session, old.claims().getId(), UUID.randomUUID().toString()))
					.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.UNAUTHORIZED));
				var fresh = new LoginSession(m.getId(), 0, UUID.randomUUID());
				String freshTokenId = UUID.randomUUID().toString();
				restoredStore.start(fresh, freshTokenId);
				assertThat(isActive(restored, fresh)).isTrue();
				assertThat(isActive(restored, session)).isFalse();

				// Keep the same process and key data while changing its role twice.
				assertThat(replacement.execInContainer("redis-cli", "REPLICAOF", "127.0.0.1", "1").getStdout().trim())
					.isEqualTo("OK");
				assertThatThrownBy(() -> restoredStore.rotate(fresh, freshTokenId, UUID.randomUUID().toString()))
					.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE));
				assertThat(replacement.execInContainer("redis-cli", "REPLICAOF", "NO", "ONE").getStdout().trim())
					.isEqualTo("OK");
				assertThat(restored.hasKey(fresh.sessionKey())).isTrue();
				assertThat(isActive(restored, fresh)).isFalse();
				assertThatThrownBy(() -> restoredStore.rotate(fresh, freshTokenId, UUID.randomUUID().toString()))
					.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.UNAUTHORIZED));
				var afterPromotion = new LoginSession(m.getId(), 0, UUID.randomUUID());
				restoredStore.start(afterPromotion, UUID.randomUUID().toString());
				assertThat(isActive(restored, afterPromotion)).isTrue();
			} finally {
				connection.destroy();
			}
		}
	}

	@Test
	void concurrentRotationCannotRestoreRevokedAccount() throws Exception {
		Member m = member(MemberStatus.ACTIVE);
		TokenResponse old = sessions.login(m.getId(), 0);
		CountDownLatch start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var rotating = pool.submit(() -> {
				start.await();
				try { return auth.reissue(old.refreshToken()); } catch (BusinessException rejected) { return null; }
			});
			var changing = pool.submit(() -> {
				start.await();
				auth.changePassword(m.getId(), new ChangePasswordRequest("original", "replacement"));
				return null;
			});
			start.countDown();
			TokenResponse renewed = rotating.get(15, TimeUnit.SECONDS);
			changing.get(15, TimeUnit.SECONDS);
			assertThat(active(old.accessToken())).isFalse();
			if (renewed != null) {
				assertThat(active(renewed.accessToken())).isFalse();
				assertThatThrownBy(() -> auth.reissue(renewed.refreshToken())).isInstanceOf(BusinessException.class);
			}
		} finally { pool.shutdownNow(); }
	}

	@Test
	void verifiedSignupIsEmailBoundAndConsumedOnceAcrossConcurrentRequests() throws Exception {
		String address = UUID.randomUUID() + "@example.invalid";
		redis.opsForValue().set(EmailType.SIGNUP.getPrefix() + address, "A2C3D4", Duration.ofMinutes(5));
		String proof = email.verifySignupCode(address, "a2c3d4");
		assertThatThrownBy(() -> email.verifySignupCode(address, "A2C3D4")).isInstanceOf(BusinessException.class);
		SignupRequest request = new SignupRequest(address, "Password!2");
		assertThatThrownBy(() -> signup.signup(request, null)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> signup.signup(new SignupRequest("other@example.invalid", "Password!2"), proof))
			.isInstanceOf(BusinessException.class);
		var pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<Boolean> submit = () -> {
			start.await();
			try { signup.signup(request, proof); return true; } catch (BusinessException rejected) { return false; }
		};
		try {
			var first = pool.submit(submit);
			var second = pool.submit(submit);
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
			assertThat(members.findByEmail(address)).isPresent();
		} finally { pool.shutdownNow(); }
	}
}
