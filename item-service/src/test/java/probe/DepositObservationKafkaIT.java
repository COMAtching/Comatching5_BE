package probe;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.comatching.item.infra.bankdeposit.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("integration")
@SpringBootTest(classes = DepositObservationKafkaIT.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.config.name=deposit-observation-it", "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:depositobservations;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "comatching.bank-probe.enabled=false", "comatching.deposit-ingest.enabled=true",
        "comatching.deposit-ingest.token=synthetic-collector-credential-0123456789",
        "spring.kafka.producer.acks=all", "spring.kafka.producer.properties[enable.idempotence]=true",
        "spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer"})
@EmbeddedKafka(partitions = 1, topics = DepositObservationPublisher.TOPIC, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Sql("/20261124_01_bank_deposit_observation.sql")
class DepositObservationKafkaIT {
    @Configuration(proxyBeanMethods = false)
    @Import({BankProbeDemoServer.class, DepositObservationController.class, DepositObservationStore.class, DepositObservationPublisher.class})
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class App {}

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired DepositObservationPublisher publisher;
    @Autowired DepositObservationStore store;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    static final String PATH = "/api/v1/collector/deposit-observations";
    static final String TOKEN = "synthetic-collector-credential-0123456789";
    static final String TOPIC = DepositObservationPublisher.TOPIC;

    ResponseEntity<String> post(String body, String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return http.postForEntity(PATH, new HttpEntity<>(body, headers), String.class);
    }

    static String row(String id, long amount) {
        return """
            {"eventId":"%s","accountAlias":"축제","capturedAt":"2026-11-24T04:00:00Z",
            "transactionDate":"2026-11-24","transactionTime":"13:00:00","direction":"CREDIT",
            "amountKrw":%d,"counterparty":null,"description":null,"bankTransactionId":null}
            """.formatted(id, amount);
    }
    static String batch(String... rows) { return "{\"observations\":[" + String.join(",",rows) + "]}"; }

    @Test
    @SuppressWarnings("unchecked")
    void httpReceiptSurvivesKafkaFailureAndReplaysKeepIdentity() throws Exception {
        String capture = UUID.randomUUID().toString();
        String a = capture + ":0:0:2", b = capture + ":0:0:3", c = capture + ":0:0:7";
        String two = batch(row(a,1000), row(b,1000));
        assertThat(post(two, "wrong").getStatusCode().value()).isEqualTo(401);
        assertThat(post(two.replace("CREDIT","DEBIT"), TOKEN).getStatusCode().value()).isEqualTo(400);
        // Exercise the same parser, projection, HTTP sender and ACK check as the extension.
        var command = new ProcessBuilder("node", "src/test/js/deposit-observation-sender.mjs", String.valueOf(port), capture).inheritIO();
        command.environment().put("TEST_DEPOSIT_TOKEN", TOKEN);
        var sender = command.start();
        try {
            assertThat(sender.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(sender.exitValue()).isZero();
        } finally {
            sender.destroyForcibly();
        }
        var accepted = post(two, TOKEN);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        assertThat(json.readTree(accepted.getBody()).get("acceptedIds").toString()).isEqualTo(json.writeValueAsString(List.of(a,b)));
        assertThat(post(two, TOKEN).getStatusCode().value()).isEqualTo(202);
        assertThat(post(batch(row(a,999), row(c,1000)), TOKEN).getStatusCode().value()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bank_deposit_observation", Integer.class)).isEqualTo(2);

        KafkaTemplate<String,Object> unavailable = mock(KafkaTemplate.class);
        when(unavailable.send(eq(TOPIC),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        new DepositObservationPublisher(store,unavailable).publish();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bank_deposit_observation WHERE published_at IS NULL",Integer.class)).isEqualTo(2);
        // Advance the lease instead of sleeping through a broker outage.
        jdbc.update("UPDATE bank_deposit_observation SET available_at=CURRENT_TIMESTAMP");
        try (var consumer = new KafkaConsumer<>(KafkaTestUtils.consumerProps("deposit-it","false",broker),
                new StringDeserializer(), new StringDeserializer())) {
            broker.consumeFromAnEmbeddedTopic(consumer, TOPIC);
            publisher.publish();
            var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 2).records(TOPIC);
            assertThat(records).extracting(r -> r.key()).containsExactlyInAnyOrder(a,b);
            for (var record : records) {
                var event = json.readTree(record.value());
                assertThat(event.get("eventId").asText()).isEqualTo(record.key());
                assertThat(event.get("amountKrw").asInt()).isEqualTo(1000);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bank_deposit_observation WHERE published_at IS NOT NULL",Integer.class)).isEqualTo(2);
            assertThat(post(two,TOKEN).getStatusCode().value()).isEqualTo(202);
            publisher.publish();
            assertThat(consumer.poll(Duration.ofMillis(250)).isEmpty()).isTrue();
            // Model a crash between a broker ACK and its DB completion marker.
            jdbc.update("UPDATE bank_deposit_observation SET published_at=NULL, available_at=CURRENT_TIMESTAMP WHERE event_id=?", a);
            publisher.publish();
            var replay = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
            assertThat(replay.key()).isEqualTo(a);
            assertThat(json.readTree(replay.value()).get("eventId").asText()).isEqualTo(a);
        }
    }
}
