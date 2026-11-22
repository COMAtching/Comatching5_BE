package com.comatching.matching.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.config.kafka.KafkaConsumerConfig;
import com.comatching.common.config.kafka.KafkaDltRedriveService;
import com.comatching.common.config.kafka.KafkaDltTopicConfig;
import com.comatching.common.config.kafka.KafkaEventRoute;
import com.comatching.common.dto.event.matching.MatchingProfileSnapshot;
import com.comatching.common.dto.event.matching.ProfileUpdatedMatchingEvent;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.repository.candidate.MatchingCandidateRepository;
import com.comatching.matching.domain.service.CandidateProfileStore;
import com.comatching.matching.domain.service.CandidateServiceImpl;
import com.comatching.matching.infra.client.MemberClient;
import com.comatching.matching.support.MySqlContainerSupport;

@DataJpaTest(properties = {
	"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
	"spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer",
	"spring.kafka.consumer.group-id=matching-candidate-sync-group",
	"spring.kafka.consumer.auto-offset-reset=earliest", "spring.kafka.consumer.enable-auto-commit=false",
	"spring.kafka.listener.ack-mode=record", "matching.kafka.candidate-listener-concurrency=1",
	"comatching.kafka.dlt-routes=CANDIDATE_PROFILE"
})
@ContextConfiguration(classes = ProfileReplayIT.Config.class)
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EmbeddedKafka(partitions = 1, topics = "profile-updates")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
@Tag("integration")
class ProfileReplayIT extends MySqlContainerSupport {
	@Configuration
	@EnableKafka
	@EntityScan(basePackageClasses = MatchingCandidate.class)
	@EnableJpaRepositories(basePackageClasses = MatchingCandidateRepository.class)
	@Import({KafkaConsumerConfig.class, KafkaDltTopicConfig.class, ProfileEventConsumer.class,
		CandidateServiceImpl.class, CandidateProfileStore.class})
	static class Config {}

	@Autowired KafkaTemplate<String, Object> kafka;
	@Autowired KafkaDltRedriveService redrive;
	@Autowired MatchingCandidateRepository candidates;
	@Autowired EmbeddedKafkaBroker broker;
	@MockitoBean MemberClient members;

	@Test
	void recoveredOlderEventCommitsOnReplayWithoutOverwritingNewerOriginal() throws Exception {
		long memberId = 90123L;
		var route = KafkaEventRoute.CANDIDATE_PROFILE;
		var parked = new TopicPartition(route.dltTopic(), 0);
		var replay = new TopicPartition(route.replayTopic(), 0);
		var old = ProfileUpdatedMatchingEvent.builder().memberId(memberId).profileId(123L).profileVersion(1L).isMatchable(true).major("이전 학과").build();
		var latest = ProfileUpdatedMatchingEvent.builder().memberId(memberId).profileId(123L).profileVersion(2L).isMatchable(true).major("최신 학과").build();
		when(members.getMatchingSnapshot(memberId)).thenThrow(new IllegalStateException("회원 서비스 일시 장애"));

		try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
			kafka.send(route.sourceTopic(), Long.toString(memberId), old).get(10, TimeUnit.SECONDS);
			var wait = Awaitility.await().atMost(Duration.ofSeconds(45));
			wait.untilAsserted(() -> assertThat(admin.listOffsets(Map.of(parked, OffsetSpec.latest()))
				.partitionResult(parked).get(5, TimeUnit.SECONDS).offset()).isOne());

			doReturn(new MatchingProfileSnapshot(memberId, MatchingProfileSnapshot.State.ACTIVE, latest, null))
				.when(members).getMatchingSnapshot(memberId);
			kafka.send(route.sourceTopic(), Long.toString(memberId), latest).get(10, TimeUnit.SECONDS);
			wait.untilAsserted(() -> assertThat(candidates.findById(memberId)).isPresent());
			assertThat(redrive.redrive(route).redriven()).isOne();
			wait.untilAsserted(() -> {
				var offsets = admin.listConsumerGroupOffsets(route.groupId()).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
				assertThat(offsets.get(replay)).isNotNull();
				assertThat(offsets.get(replay).offset()).isOne();
			});
			var candidate = candidates.findById(memberId).orElseThrow();
			assertThat(candidate.getLastProfileVersion()).isEqualTo(2L);
			assertThat(candidate.getMajor()).isEqualTo("최신 학과");
			assertThat(admin.listOffsets(Map.of(parked, OffsetSpec.latest())).partitionResult(parked)
				.get(5, TimeUnit.SECONDS).offset()).isOne();
		}
	}
}
