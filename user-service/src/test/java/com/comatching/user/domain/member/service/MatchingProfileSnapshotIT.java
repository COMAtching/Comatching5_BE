package com.comatching.user.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.domain.enums.MemberStatus;
import com.comatching.common.dto.event.matching.MatchingProfileSnapshot.State;
import com.comatching.common.service.S3Service;
import com.comatching.user.domain.event.MemberOutboxRepository;
import com.comatching.user.domain.event.UserEventPublisher;
import com.comatching.user.domain.member.dto.ProfileUpdateRequest;
import com.comatching.user.domain.member.entity.Member;
import com.comatching.user.domain.member.entity.Profile;
import com.comatching.user.domain.member.entity.ProfileHobby;
import com.comatching.user.domain.member.repository.MemberRepository;
import com.comatching.user.domain.member.repository.ProfileRepository;
import com.comatching.user.global.config.ProfileImageProperties;
import com.comatching.user.support.OutboxMySqlSupport;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MatchingProfileSnapshotIT.Config.class)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Tag("integration")
class MatchingProfileSnapshotIT extends OutboxMySqlSupport {
	@Configuration
	@EntityScan(basePackageClasses = Member.class)
	@EnableJpaRepositories(basePackageClasses = MemberRepository.class)
	@Import({ProfileServiceImpl.class, UserEventPublisher.class, MemberOutboxRepository.class})
	static class Config {}

	@Autowired ProfileManageService service;
	@Autowired MemberRepository members;
	@Autowired ProfileRepository profiles;
	@Autowired PlatformTransactionManager tm;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean ProfileImageProperties images;
	@MockitoBean S3Service s3;

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentEditsPublishDistinctVersionsAndRecoveryReadsDontAdvanceThem() throws Exception {
		var member = members.save(Member.builder().role(MemberRole.ROLE_USER).status(MemberStatus.ACTIVE)
			.email("version-test@example.invalid").build());
		long memberId = member.getId();
		profiles.save(Profile.builder().member(member).gender(Gender.MALE).birthDate(LocalDate.of(2000, 1, 1))
			.major("원래 학과").mbti("ISTJ").university("대학").contactFrequency(ContactFrequency.FREQUENT)
			.hobbies(List.of(new ProfileHobby(HobbyCategory.SPORTS, "축구"), new ProfileHobby(HobbyCategory.CULTURE, "영화")))
			.build());
		var pool = Executors.newFixedThreadPool(2);
		try {
			assertThat(service.getMatchingSnapshot(memberId).profile().profileVersion()).isZero();
			var start = new CountDownLatch(1);
			var first = pool.submit(() -> { start.await(); return service.updateProfile(memberId, update("A")); });
			var second = pool.submit(() -> { start.await(); return service.updateProfile(memberId, update("B")); });
			start.countDown();
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);

			var tx = new TransactionTemplate(tm);
			tx.executeWithoutResult(status -> {
				service.updateProfile(memberId, update("롤백할 학과"));
				status.setRollbackOnly();
			});
			var snapshot = service.getMatchingSnapshot(memberId);
			assertThat(snapshot.state()).isEqualTo(State.ACTIVE);
			assertThat(snapshot.profile().profileVersion()).isEqualTo(2L);
			assertThat(snapshot.profile().hobbyCategories()).containsExactlyInAnyOrder(HobbyCategory.SPORTS, HobbyCategory.CULTURE);
			assertThat(jdbc.queryForList("""
				SELECT JSON_EXTRACT(payload, '$.profileVersion') FROM member_outbox_events
				WHERE member_id=? AND topic='profile-updates' ORDER BY id
				""", Long.class, memberId)).containsExactly(1L, 2L);
			assertThat(jdbc.queryForObject("""
				SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.major')) FROM member_outbox_events
				WHERE member_id=? AND topic='profile-updates' ORDER BY id DESC LIMIT 1
				""", String.class, memberId)).isEqualTo(snapshot.profile().major());

			tx.executeWithoutResult(status -> members.findById(memberId).orElseThrow().withdraw());
			var withdrawn = service.getMatchingSnapshot(memberId);
			assertThat(withdrawn.state()).isEqualTo(State.WITHDRAWN);
			assertThat(withdrawn.withdrawnAt()).isNotNull();
			assertThat(withdrawn.profile()).isNull();
		} finally {
			pool.shutdownNow();
			jdbc.update("DELETE FROM member_outbox_events WHERE member_id=?", memberId);
			members.deleteById(memberId);
		}
	}

	@Test
	void missingMemberHasNoActiveProfile() {
		assertThat(service.getMatchingSnapshot(-1L).state()).isEqualTo(State.NO_ACTIVE_PROFILE);
	}

	private ProfileUpdateRequest update(String major) {
		var update = new ProfileUpdateRequest();
		update.setMajor(major);
		return update;
	}
}
