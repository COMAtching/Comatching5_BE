package com.comatching.item.infra.bankdeposit;

import com.comatching.common.config.kafka.KafkaEventRoute;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="comatching.deposit-ingest.enabled", havingValue="true")
public class BankScanKafka {
    public record Completion(java.util.UUID scanId) {}
    public static final String TOPIC="kb-deposit-scan-completed-v1";
    private final JdbcTemplate db;
    private final KafkaTemplate<String,Object> kafka;
    private final BankApprovalService approvals;

    // Multiple publishers may deliver the same scan. The consumer locks the scan and commits once.
    public void publish() throws Exception {
        var rows=db.queryForList("SELECT scan_id FROM bank_deposit_scan WHERE published_at IS NULL ORDER BY seq LIMIT 1",String.class);
        if(rows.isEmpty()) return;
        String id=rows.get(0);
        kafka.send(TOPIC,"kb",new Completion(java.util.UUID.fromString(id))).get(10,TimeUnit.SECONDS);
        db.update("UPDATE bank_deposit_scan SET published_at=? WHERE scan_id=? AND published_at IS NULL",LocalDateTime.now(BankScanService.SEOUL),id);
    }
    @KafkaListener(topics={TOPIC,KafkaEventRoute.ReplayTopics.BANK_SCAN}, groupId="item-bank-approval",properties="auto.offset.reset=earliest")
    public void receive(Completion completion) {
        approvals.process(completion.scanId().toString());
    }
}
