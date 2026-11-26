package probe;

import com.comatching.common.config.kafka.*;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.outbox.OrderGrantOutboxRepository;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.order.service.OrderDecisionLogService;
import com.comatching.item.domain.order.service.OrderOutboxService;
import com.comatching.item.domain.product.service.AdminPaymentService;
import com.comatching.item.domain.product.service.AdminPaymentServiceImpl;
import com.comatching.item.infra.bankdeposit.*;
import com.comatching.item.infra.bankdeposit.DepositObservationController.Observation;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Tag("integration")
@SpringBootTest(classes=BankApprovalIT.App.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"spring.config.name=bank-approval-it","server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:bankapproval;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop","comatching.kafka.dlt-routes=BANK_SCAN","comatching.deposit-ingest.token=example-only-collector-credential-123456789","comatching.deposit-ingest.enabled=true",
        "spring.kafka.producer.acks=all","spring.kafka.producer.value-serializer=com.comatching.common.config.kafka.EventValueSerializer"})
@EmbeddedKafka(partitions=1,topics={BankScanKafka.TOPIC,KafkaEventRoute.ReplayTopics.BANK_SCAN,BankApprovalIT.DLT},
    bootstrapServersProperty="spring.kafka.bootstrap-servers")
@Sql(executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS,scripts={"/20261124_01_bank_deposit_observation.sql","/20261125_01_bank_approval.sql"})
class BankApprovalIT {
    @Configuration(proxyBeanMethods=false)
    @EnableKafka @EnableJpaAuditing
    @EntityScan(basePackageClasses=Order.class)
    @EnableJpaRepositories(basePackageClasses=OrderRepository.class)
    @ImportAutoConfiguration({HibernateJpaAutoConfiguration.class,KafkaAutoConfiguration.class})
    @Import({KafkaConsumerConfig.class,BankProbeDemoServer.class, BankScanKafka.class, BankScanService.class, BankApprovalGate.class,
        BankApprovalService.class, BankScanController.class, DepositObservationController.class, DepositObservationStore.class, AdminPaymentServiceImpl.class,
        OrderGrantOutboxRepository.class,OrderDecisionLogService.class, OrderOutboxService.class})
    static class App {}

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired BankApprovalService approvals;
    @Autowired BankScanService scans;
    @Autowired BankScanKafka publisher;
    @Autowired DepositObservationStore raw;
    @Autowired OrderRepository orders;
    @Autowired AdminPaymentService admin;
    @Autowired JdbcTemplate db;
    @Autowired KafkaDltRedriveService redrive;
    @Autowired org.springframework.kafka.test.EmbeddedKafkaBroker broker;
    static final String DLT=BankScanKafka.TOPIC+".item-bank-approval.DLT";
    static final ZoneId KOREA=ZoneId.of("Asia/Seoul");
    LocalDateTime paid;
    static final String ACCOUNT="a".repeat(64);

    @BeforeEach void reset() {
        for(String table:List.of("bank_scan_capture","bank_deposit_observation","bank_deposit_scan","bank_deposit_ledger","bank_order_review",
            "bank_approval_switch","order_decision_logs","order_outbox_events","purchase_order")) db.update("DELETE FROM "+table);
        db.update("DELETE FROM bank_approval_control");
        db.update("INSERT INTO bank_approval_control(id) VALUES(1)");
        paid=LocalDateTime.now(KOREA).minusSeconds(10).withNano(0);
        approvals.toggle(true,0,42);
        db.update("UPDATE bank_approval_control SET switched_at=? WHERE id=1",paid.minusMinutes(1));
    }

    long order(String payer) {
        var before=paid.atZone(KOREA).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().minusMinutes(2);
        return orders.saveAndFlush(Order.builder().memberId(1L).requestedItemName("이용권").requesterRealName(payer)
            .requesterUsername("fixture").requestedPrice(1000).expectedPrice(1000)
            .requestedAt(before).expiresAt(before.plusMinutes(10)).build()).getId();
    }

    BankScanService.Scan capture(String... payers) {
        var capture=UUID.randomUUID();
        var at=OffsetDateTime.now();
        var ids=new ArrayList<String>();
        var rows=new ArrayList<Observation>();
        for(String payer:payers) {
            String id=capture+":0:1:"+(ids.size()+1);
            rows.add(new Observation(id,"테스트",at,paid.toLocalDate(),paid.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                "CREDIT",1000,payer,null,null));
            ids.add(id);
        }
        for (int i=0;i<rows.size();i+=100) raw.accept(rows.subList(i,Math.min(rows.size(),i+100)));
        return new BankScanService.Scan(UUID.randomUUID(),ACCOUNT,at,at,paid.minusHours(1),paid,
            List.of(new BankScanService.Page(1,capture,ids.size(),ids.size(),ids)));
    }
    long count(String table){ return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    String process(BankScanService.Scan scan) {
        var receipt=scans.accept(scan);
        approvals.process(receipt.scanId().toString());
        return receipt.issue();
    }

    @Test void completeScanThroughKafkaUsesTheExistingGrantAndOutboxExactlyOnce() throws Exception {
        long id=order("단독");
        var sender=new ProcessBuilder("node","src/test/js/deposit-scan-sender.mjs",String.valueOf(port),
            paid.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))).inheritIO();
        sender.environment().put("TEST_DEPOSIT_TOKEN","example-only-collector-credential-123456789");
        var client=sender.start();
        try {
            assertThat(client.waitFor(15,TimeUnit.SECONDS)).isTrue();
            assertThat(client.exitValue()).isZero();
        } finally { client.destroyForcibly(); }
        String scanId=db.queryForObject("SELECT scan_id FROM bank_deposit_scan",String.class);
        assertThat(count("order_decision_logs")).isZero();
        publisher.publish();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(count("order_decision_logs")).isOne());
        approvals.process(scanId);
        assertThat(process(capture("단독"))).isNull();
        assertThat(count("order_outbox_events")).isOne();
        assertThat(count("bank_deposit_ledger")).isOne();
        assertThat(admin.getGrant(id).attempt()).isOne();
        assertThat(admin.getGrant(id).grantStatus().name()).isEqualTo("PENDING");
    }

    @Test void processingFailureRollsBackThenRecoversFromThePaymentDlt() throws Exception {
        long id=order("복구"); var scan=capture("복구"); scans.accept(scan);
        db.execute("ALTER TABLE order_decision_logs ADD CONSTRAINT test_block_approval CHECK (reason IS NULL)");
        try (var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(
            org.springframework.kafka.test.utils.KafkaTestUtils.consumerProps("bank-dlt-test","false",broker),
            new org.apache.kafka.common.serialization.StringDeserializer(),new org.apache.kafka.common.serialization.StringDeserializer())) {
            broker.consumeFromAnEmbeddedTopic(consumer,DLT);
            publisher.publish();
            var failed=org.springframework.kafka.test.utils.KafkaTestUtils.getSingleRecord(consumer,DLT,Duration.ofSeconds(25));
            assertThat(failed.value()).contains(scan.scanId().toString());
            assertThat(admin.getGrant(id).status().name()).isEqualTo("PENDING");
            assertThat(approvals.review(0,10)).hasSize(1);
            assertThat(count("order_outbox_events")).isZero();
        } finally {
            db.execute("ALTER TABLE order_decision_logs DROP CONSTRAINT test_block_approval");
        }
        assertThat(redrive.redrive(KafkaEventRoute.BANK_SCAN).redriven()).isOne();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(count("order_decision_logs")).isOne());
        approvals.process(scan.scanId().toString());
        assertThat(admin.getGrant(id).attempt()).isOne();
        assertThat(count("order_outbox_events")).isOne();
    }

    @Test void emptyCompletedScanRestoresHeartbeatAfterSevenMinutes() {
        var meters=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var metrics=new BankCollectorMetrics(meters,approvals);
        db.update("UPDATE bank_approval_control SET switched_at=?,issue='READ_FAILED',queue_depth=4 WHERE id=1",
            LocalDateTime.now(KOREA).minusMinutes(8));
        metrics.sample();
        assertThat(meters.get("bank.collector.silence.seconds").gauge().value()).isGreaterThanOrEqualTo(420);
        assertThat(meters.get("bank.collector.issue").gauge().value()).isOne();
        assertThat(meters.get("bank.collector.queue.batches").gauge().value()).isEqualTo(4);
        assertThat(process(capture())).isNull();
        metrics.sample();
        assertThat(meters.get("bank.collector.silence.seconds").gauge().value()).isLessThan(5);
        assertThat(meters.get("bank.collector.issue").gauge().value()).isZero();
        assertThat(count("bank_deposit_ledger")).isZero();
    }

    @Test void oldAndUnmatchedDepositsRemainAvailableForReview() {
        order("이전"); paid=paid.minusMinutes(2);
        assertThat(process(capture("이전","주문없음"))).isNull();
        assertThat(approvals.review(0,20)).hasSize(2).allMatch(row->row.reason().equals("MANUAL_PERIOD"));
        assertThat(count("order_outbox_events")).isZero();
    }

    @Test void oneDepositForTwoOpenSameNameOrdersWaitsForTheOperator() {
        long first = order("동명이인"), second = order("동명이인");
        assertThat(process(capture("동명이인"))).isNull();
        var review = approvals.review(0,20,true);
        assertThat(review).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo("MULTIPLE_ORDERS");
            assertThat(row.candidates()).extracting(BankApprovalService.Candidate::id).containsExactly(first,second);
        });
        assertThat(count("order_decision_logs")).isZero();
        assertThat(count("order_outbox_events")).isZero();
    }

    @Test void reviewFiltersBeforePagingAndKeepsUnmatchedAmountsInTheLedger() {
        var scan = capture("소액","주문자");
        String testId = scan.pages().get(0).ids().get(0);
        db.update("UPDATE bank_deposit_observation SET payload=REPLACE(payload,?,?) WHERE event_id=?",
            "\"amountKrw\":1000","\"amountKrw\":1",testId);
        order("주문자");
        approvals.toggle(false,1,42);
        process(scan);
        var all = approvals.review(0,20,false);
        assertThat(all).extracting(BankApprovalService.Review::amount).containsExactlyInAnyOrder(1L,1000L);
        var matching = approvals.review(0,1,true);
        assertThat(matching).extracting(BankApprovalService.Review::amount).containsExactly(1000L);
        assertThat(approvals.review(matching.get(0).id(),1,true)).isEmpty();
    }

    @Test void observationsFromCompletedCapturesAreExcludedFromRawReview() {
        var completed = capture("완료");
        var pending = capture("미완료");
        process(completed);
        assertThat(raw.recentUnreconciled()).extracting(Observation::eventId)
            .containsExactly(pending.pages().get(0).ids().get(0));
        assertThat(raw.recent()).extracting(Observation::eventId).containsExactlyInAnyOrder(
            completed.pages().get(0).ids().get(0), pending.pages().get(0).ids().get(0));
    }

    @Test void rawHistoryShowsTheLatestThirtyEvenWhenAllCapturesAreCompleted() {
        String[] payers = new String[32];
        Arrays.fill(payers,"합성");
        var scan = capture(payers);
        process(scan);
        var ids = scan.pages().get(0).ids();
        for (int i=0; i<ids.size(); i++) {
            db.update("UPDATE bank_deposit_observation SET received_at=? WHERE event_id=?",paid.plusSeconds(i),ids.get(i));
        }
        var newest = new ArrayList<>(ids.subList(2,32));
        Collections.reverse(newest);
        assertThat(raw.recent()).extracting(Observation::eventId).containsExactlyElementsOf(newest);
        assertThat(raw.recentUnreconciled()).isEmpty();
    }

    @Test void twoDifferentSecondsCannotFundOneOrderAutomatically() {
        order("두번");
        var scan=capture("두번","두번");
        var id=scan.pages().get(0).ids().get(0);
        String recent=paid.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String older=paid.minusSeconds(1).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        db.update("UPDATE bank_deposit_observation SET payload=REPLACE(payload,?,?) WHERE event_id=?",recent,older,id);
        assertThat(process(scan)).isNull();
        assertThat(approvals.review(0,20)).hasSize(2).allMatch(row->row.reason().equals("MANUAL_REVIEW"));
        assertThat(count("order_outbox_events")).isZero();
    }

    @Test void threeIndistinguishablePaymentsStayHeldAfterManualAndCancellation() {
        long first=order("동명"), second=order("동명"), third=order("동명");
        for(int i=0;i<3;i++) assertThat(process(capture("동명","동명","동명"))).isNull();
        assertThat(count("bank_deposit_ledger")).isEqualTo(3);
        assertThat(count("order_outbox_events")).isZero();
        var rows=approvals.review(0,20);
        assertThat(rows).allMatch(r->r.candidates().size()==3);
        approvals.manual(rows.get(0).id(),first,42); approvals.manual(rows.get(0).id(),first,42);
        admin.rejectPurchase(second,42L);
        assertThat(process(capture("동명","동명","동명"))).isNull();
        assertThat(approvals.review(0,20)).hasSize(2);
        assertThatThrownBy(()->approvals.manual(rows.get(0).id(),third,42)).isInstanceOf(ResponseStatusException.class);
        assertThat(admin.getGrant(third).status().name()).isEqualTo("PENDING");
        assertThat(count("order_outbox_events")).isEqualTo(2); // one approval, one rejection
    }

    @Test void twoThousandIdenticalPaymentsSurviveRecaptureWithoutAnApproval() {
        order("행사"); order("행사");
        String[] payers=new String[2000]; Arrays.fill(payers,"행사");
        assertThat(process(capture(payers))).isNull();
        assertThat(process(capture(payers))).isNull();
        assertThat(count("bank_deposit_ledger")).isEqualTo(2000);
        assertThat(db.queryForObject("SELECT SUM(amount_krw) FROM bank_deposit_ledger",Long.class)).isEqualTo(2000000L);
        assertThat(count("order_decision_logs")).isZero();
    }

    @Test void missingObservationsAndRegressingCountsCannotAdvance() {
        order("동명"); var scan=capture("동명","동명");
        var page=scan.pages().get(0);
        var partial=new BankScanService.Scan(UUID.randomUUID(),ACCOUNT,scan.startedAt(),scan.finishedAt(),
            scan.fromSecond(),scan.throughSecond(),List.of(new BankScanService.Page(1,page.captureId(),3,3,
                List.of(page.ids().get(0),page.ids().get(1),page.captureId()+":0:1:3"))));
        assertThatThrownBy(()->scans.accept(partial)).isInstanceOfSatisfying(ResponseStatusException.class,
            error->assertThat(error.getStatusCode().value()).isEqualTo(425));
        assertThat(count("bank_deposit_scan")).isZero();
        assertThat(approvals.status().checkpoint()).isNull();
        process(scan);
        String before=approvals.status().checkpoint();
        assertThat(process(capture("동명"))).isEqualTo("COUNT_CONFLICT");
        assertThat(approvals.status().checkpoint()).isEqualTo(before);
        assertThat(count("bank_deposit_ledger")).isEqualTo(2);
        assertThat(count("order_outbox_events")).isZero();
    }

    @Test void switchingOffHoldsOldWorkEvenWhenReenabled() {
        order("단독"); var scan=capture("단독"); scans.accept(scan);
        approvals.toggle(false,1,42); approvals.toggle(true,2,42);
        approvals.process(scan.scanId().toString());
        assertThat(approvals.review(0,20)).extracting(BankApprovalService.Review::reason).containsExactly("MANUAL_PERIOD");
        assertThat(count("order_decision_logs")).isZero();
    }

    @Test void manualAndAutomaticRaceOnlyQueueOneGrant() throws Exception {
        long id=order("단독"); var scan=capture("단독"); scans.accept(scan);
        long d=approvals.review(0,20).get(0).id();
        var manual=CompletableFuture.runAsync(()->approvals.manual(d,id,42));
        var auto=CompletableFuture.runAsync(()->approvals.process(scan.scanId().toString()));
        CompletableFuture.allOf(manual,auto).get(10,TimeUnit.SECONDS);
        assertThat(admin.getGrant(id).attempt()).isOne();
        assertThat(count("order_outbox_events")).isOne();
    }
}
