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

import java.util.concurrent.TimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
import org.springframework.test.annotation.DirtiesContext;
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
import com.comatching.user.domain.mail.service.VerificationMailStore;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.domain.member.service.MemberServiceImpl;
import com.comatching.user.domain.member.service.ProfileServiceImpl;
import com.comatching.user.global.config.ProfileImageProperties;
import com.comatching.user.global.security.cookie.AuthCookieFactory;
import com.comatching.user.global.security.handler.LoginSessionWriter;
import com.comatching.user.global.security.oauth2.handler.CustomOAuth2SuccessHandler;
import com.comatching.user.global.security.oauth2.handler.CustomOAuth2FailureHandler;
import com.comatching.user.global.security.oauth2.service.SocialLoginService;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.user.OAuth2User;
import com.comatching.user.global.security.oauth2.provider.kakao.config.KakaoProperties;
import com.comatching.user.global.security.oauth2.provider.kakao.unlink.KakaoAuthClient;
import com.comatching.user.global.security.oauth2.user.CustomOAuth2User;
import com.comatching.user.support.OutboxMySqlSupport;
import com.comatching.user.domain.report.MemberReport;
import com.comatching.user.domain.report.ReportRepository;
import com.comatching.user.domain.report.ReportService;
import com.comatching.user.infra.client.ReportMatchClient;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.repository.ProfileRepository;
import com.comatching.user.domain.operations.FeatureOperation;
import com.comatching.user.domain.operations.FeatureOperationRepository;
import com.comatching.user.domain.operations.FeatureOperations;
import com.comatching.user.domain.operations.MemberSessionShutdown;
import com.comatching.common.operations.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;

@DataJpaTest(showSql = false, properties = {
	"spring.jpa.hibernate.ddl-auto=create-drop", "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
	"jwt.access-token.expiration=60000", "jwt.refresh-token.expiration=120000"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AccountSecurityIT.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("integration")
@DirtiesContext
class AccountSecurityIT extends OutboxMySqlSupport {
	@Configuration
	@EntityScan(basePackageClasses = {Member.class, FeatureOperation.class, MemberReport.class})
	@EnableJpaRepositories(basePackageClasses = {MemberRepository.class, FeatureOperationRepository.class, ReportRepository.class})
	@Import({ReportService.class, FeatureOperations.class, MemberSessionShutdown.class, MemberServiceImpl.class, LoginSessionStore.class, LoginSessionService.class, JwtUtil.class,
		AuthServiceImpl.class, SignupServiceImpl.class, EmailServiceImpl.class, VerificationMailStore.class, ProfileServiceImpl.class})
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

	@Autowired ReportService reports;
	@Autowired ProfileRepository profiles;
	@MockitoBean ReportMatchClient reportMatches;
	@Autowired AuthService auth;
	@Autowired LoginSessionService sessions;
	@Autowired MemberRepository members;
	@Autowired com.comatching.user.domain.member.service.MemberService memberService;
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
	@Autowired FeatureOperations features;
	@Autowired PlatformTransactionManager transactions;

	private void setService(boolean open) {
		var current = features.state(Feature.LOGIN);
		if (current.open() != open) features.change(Feature.LOGIN, open, "", current.version(), 1);
	}

	@org.junit.jupiter.api.AfterEach
	void reopenService() { setService(true); }
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
	void serviceClosureRevokesEveryOrdinaryLoginAndPreservesAdminAndIndividualSettings() {
		Member customer = member(MemberStatus.ACTIVE);
		var first = sessions.login(customer.getId(), 0);
		var second = sessions.login(customer.getId(), 0);
		var guest = members.saveAndFlush(Member.builder().role(MemberRole.ROLE_GUEST).status(MemberStatus.ACTIVE)
			.email(UUID.randomUUID() + "@example.invalid").build());
		var guestTokens = sessions.login(guest.getId(), 0);
		// Existing deployments stored the numeric version and an unqualified session stamp.
		var legacy = jwt.readAccess(guestTokens.accessToken()).session();
		redis.execute(new DefaultRedisScript<>(SessionRedisScripts.SESSION_STAMP + """
			redis.call('SET', KEYS[1], ARGV[1], 'PX', 60000)
			redis.call('SET', KEYS[2], sessionStamp, 'PX', 60000)
			return 1
			""", Long.class), List.of(LoginSession.versionKey(guest.getId()), legacy.sessionKey()), "0");
		assertThat(active(guestTokens.accessToken())).isTrue();
		Member admin = members.saveAndFlush(Member.builder().role(MemberRole.ROLE_ADMIN).status(MemberStatus.ACTIVE)
			.email(UUID.randomUUID() + "@example.invalid").build());
		var adminTokens = sessions.login(admin.getId(), 0);
		var chat = features.adminSnapshot().stream().filter(s -> s.feature() == Feature.CHAT).findFirst().orElseThrow();
		features.change(Feature.CHAT, false, "", chat.version(), 1);
		try {
			setService(false);
			assertThat(features.snapshot()).allMatch(s -> !s.open());
			assertThat(active(first.accessToken())).isFalse();
			assertThat(active(second.accessToken())).isFalse();
			assertThat(active(guestTokens.accessToken())).isFalse();
			assertThatThrownBy(() -> sessions.login(customer.getId(), 0)).isInstanceOf(BusinessException.class);
			assertThatThrownBy(() -> auth.reissue(second.refreshToken())).isInstanceOf(BusinessException.class);
			assertThat(active(auth.reissue(adminTokens.refreshToken()).accessToken())).isTrue();
			assertThat(active(sessions.login(admin.getId(), 0).accessToken())).isTrue();
			setService(true);
			assertThat(features.state(Feature.CHAT).open()).isFalse();
			assertThat(features.state(Feature.MATCHING).open()).isTrue();
			assertThat(active(sessions.login(guest.getId(), 0).accessToken())).isTrue();
			assertThat(active(guestTokens.accessToken())).isFalse();
			assertThat(active(sessions.login(customer.getId(), 0).accessToken())).isTrue();
			assertThat(active(first.accessToken())).isFalse();
			assertThat(active(second.accessToken())).isFalse();
			assertThatThrownBy(() -> auth.reissue(second.refreshToken())).isInstanceOf(BusinessException.class);
		} finally {
			features.change(Feature.CHAT, chat.open(), chat.message(), chat.version() + 1, 1);
		}
	}

	@Test
	void closingWaitsForAnAdmittedLoginAndRevokesItsSession() throws Exception {
		Member customer = member(MemberStatus.ACTIVE);
		var pool = Executors.newFixedThreadPool(2);
		var admitted = new CountDownLatch(1);
		var finishLogin = new CountDownLatch(1);
		var closing = new CountDownLatch(1);
		var seconds = TimeUnit.SECONDS;
		try {
			var login = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
				var issued = sessions.login(customer.getId(), 0);
				admitted.countDown();
				try {
					if (!finishLogin.await(10, seconds)) throw new AssertionError("Login did not finish");
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError(e);
				}
				return issued;
			}));
			assertThat(admitted.await(10, seconds)).isTrue();
			var close = pool.submit(() -> { closing.countDown(); setService(false); });
			assertThat(closing.await(10, seconds)).isTrue();
			assertThatThrownBy(() -> close.get(200, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);
			finishLogin.countDown();
			var issued = login.get(10, seconds);
			close.get(10, seconds);
			assertThat(active(issued.accessToken())).isFalse();
		} finally {
			finishLogin.countDown();
			pool.shutdownNow();
		}
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

	@Test
	void reviewedSuspensionInvalidatesSessionsUntilLifted() {
		Member target = member(MemberStatus.ACTIVE);
		long id = target.getId();
		profiles.save(Profile.builder().member(target).nickname("매칭상대").gender(Gender.MALE)
			.birthDate(LocalDate.of(2000, 1, 1)).mbti("ISTJ").university("대학").major("전공")
			.contactFrequency(ContactFrequency.NORMAL).build());
		var first = sessions.login(id, 0);
		var second = sessions.login(id, 0);
		given(reportMatches.arePartners(800L, id)).willReturn(true);
		long report = reports.submit(800L, new ReportService.Submission(UUID.randomUUID(), id, MemberReport.Source.CHAT, "반복 위반"));
		reports.decide(report, 90L, new ReportService.Decision(ReportService.Action.SUSPEND, "검토 후 정지"));

		assertThat(active(first.accessToken())).isFalse();
		assertThat(active(second.accessToken())).isFalse();
		assertThatThrownBy(() -> auth.reissue(second.refreshToken())).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> sessions.login(id, 1)).isInstanceOf(BusinessException.class);
		reports.lift(report, 90L);
		assertThat(active(sessions.login(id, 1).accessToken())).isTrue();
		assertThat(active(first.accessToken())).isFalse();
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
			new AuthCookieFactory(false, "", "Lax", "test-install-secret"), new ObjectMapper()));
		ReflectionTestUtils.setField(handler, "clientUrl", "http://localhost");
		var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		var response = new MockHttpServletResponse();
		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
		assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/main");
		assertThat(response.getHeaders("Set-Cookie")).hasSize(3);
		auth.changePassword(m.getId(), new ChangePasswordRequest("original", "replacement"));
		var staleResponse = new MockHttpServletResponse();
		handler.onAuthenticationSuccess(new MockHttpServletRequest(), staleResponse, authentication);
		assertThat(staleResponse.getStatus()).isEqualTo(401);
		assertThat(staleResponse.getHeaders("Set-Cookie")).isEmpty();
	}

	@Test
	void closedKakaoCallbackReturnsConflictWithoutCreatingAGuestOrSession() throws Exception {
		var registration = mock(ClientRegistration.class);
		given(registration.getRegistrationId()).willReturn("kakao");
		var kakaoUser = mock(OAuth2User.class);
		String subject = UUID.randomUUID().toString();
		given(kakaoUser.getAttributes()).willReturn(java.util.Map.of("sub", subject, "email", "new@example.invalid"));
		setService(false);
		var callback = new SocialLoginService(memberService);
		var denied = org.junit.jupiter.api.Assertions.assertThrows(AuthenticationServiceException.class,
			() -> callback.processUser(registration, kakaoUser));
		var response = new MockHttpServletResponse();
		new CustomOAuth2FailureHandler(new LoginSessionWriter(sessions, new AuthCookieFactory(false, "", "Lax"),
			new ObjectMapper())).onAuthenticationFailure(new MockHttpServletRequest(), response, denied);
		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getContentAsString()).contains("FEATURE_CLOSED");
		assertThat(response.getHeaders("Set-Cookie")).isEmpty();
		assertThat(members.findBySocialTypeAndSocialId(com.comatching.common.domain.enums.SocialType.KAKAO, subject)).isEmpty();
	}

	@Test
	void signedInstallationMovesOnLoginAndStaleLogoutCannotDetachItsNewOwner() throws Exception {
		Member a = member(MemberStatus.ACTIVE), b = member(MemberStatus.ACTIVE);
		var cookies = new AuthCookieFactory(false, "", "Lax", "test-install-secret");
		UUID browser = UUID.randomUUID(), another = UUID.randomUUID();
		var old = sessions.login(a.getId(), 0, browser);
		var other = sessions.login(a.getId(), 0, another);
		String signedCookie = cookies.installation(browser).getValue();
		assertThat(cookies.installationId(signedCookie)).isEqualTo(browser);
		assertThat(cookies.installationId(browser.toString())).isNull();
		assertThat(cookies.installationId(another + signedCookie.substring(36))).isNull();
		var installations = new com.comatching.common.auth.PushInstallations(redis);
		assertThat(installations.register(browser, jwt.readAccess(old.accessToken()).session(), "a-test")).isTrue();
		assertThat(installations.register(another, jwt.readAccess(other.accessToken()).session(), "a-other")).isTrue();
		var writer = new LoginSessionWriter(sessions, cookies, new ObjectMapper());
		var request = new MockHttpServletRequest();
		request.setCookies(new jakarta.servlet.http.Cookie(com.comatching.common.auth.PushInstallations.COOKIE, signedCookie));
		var response = new MockHttpServletResponse();
		var principal = new com.comatching.user.global.security.UserPrincipal(MemberLoginDto.builder().id(b.getId())
			.authVersion(0).email(b.getEmail()).role("ROLE_USER").status("ACTIVE").build());
		assertThat(writer.establish(principal, request, response)).isTrue();
		String access = response.getHeaders("Set-Cookie").stream().filter(c -> c.startsWith("accessToken="))
			.findFirst().orElseThrow().split(";", 2)[0].substring("accessToken=".length());
		var current = jwt.readAccess(access).session();
		assertThat(active(old.accessToken())).isFalse();
		assertThat(active(other.accessToken())).isTrue();
		assertThat(active(access)).isTrue();
		assertThat(sessions.prepareInstallation(UUID.randomUUID(), current)).isEqualTo(browser);
		assertThat(installations.target(browser, a.getId())).isNull();
		assertThat(installations.register(browser, current, "b-test")).isTrue();
		assertThatThrownBy(() -> sessions.prepareInstallation(UUID.randomUUID(), jwt.readAccess(old.accessToken()).session()))
			.isInstanceOf(BusinessException.class);
		auth.logout(old.accessToken(), old.refreshToken());
		assertThat(installations.target(browser, b.getId()).token()).isEqualTo("b-test");
		assertThat(installations.target(another, a.getId()).token()).isEqualTo("a-other");
		auth.withdraw(a.getId());
		assertThat(installations.installations(a.getId())).isEmpty();
		assertThat(installations.target(browser, b.getId())).isNotNull();
		auth.logout(access, null);
		assertThat(installations.target(browser, b.getId())).isNull();
	}

	@Test
	void switchingAccountsWithOnlyPreMigrationCookiesEndsOnlyThePresentedSession() {
		Member previous = member(MemberStatus.ACTIVE), next = member(MemberStatus.ACTIVE);
		var browser = sessions.login(previous.getId(), 0);
		var secondDevice = sessions.login(previous.getId(), 0);
		var replacement = sessions.login(next.getId(), 0, UUID.randomUUID(), browser.accessToken(), null);
		assertThat(active(browser.accessToken())).isFalse();
		assertThat(active(secondDevice.accessToken())).isTrue();
		auth.logout(browser.accessToken(), browser.refreshToken());
		assertThat(active(replacement.accessToken())).isTrue();
		var staleMarker = jwt.readAccess(browser.accessToken()).session().clientMarker();
		assertThatThrownBy(() -> auth.logout(replacement.accessToken(), replacement.refreshToken(), staleMarker))
			.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(GeneralErrorCode.FORBIDDEN));
		assertThat(active(replacement.accessToken())).isTrue();
		assertThat(auth.logout(browser.accessToken(), browser.refreshToken(), staleMarker)).isFalse();
		assertThat(auth.logout(replacement.accessToken(), replacement.refreshToken(),
			jwt.readAccess(replacement.accessToken()).session().clientMarker())).isTrue();
		assertThat(active(replacement.accessToken())).isFalse();
		assertThat(active(secondDevice.accessToken())).isTrue();
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
