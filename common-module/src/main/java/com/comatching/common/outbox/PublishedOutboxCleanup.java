package com.comatching.common.outbox;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(name = "comatching.outbox.cleanup-target")
public class PublishedOutboxCleanup {
    enum Target {
        MEMBER("member_outbox_events"),
        MATCHING("matching_outbox_events"),
        ORDER("order_outbox_events", "order_grant_outbox_events");

        final List<String> tables;
        Target(String... tables) { this.tables = List.of(tables); }
    }

    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbc;
    private final Target target;

    public PublishedOutboxCleanup(JdbcTemplate jdbc, @Value("${comatching.outbox.cleanup-target}") Target target) {
        this.jdbc = jdbc;
        this.target = target;
    }

    @Scheduled(cron = "${comatching.outbox.cleanup-cron}", zone = "Asia/Seoul")
    public void clean() {
        var cutoff = LocalDateTime.now().minusDays(7);
        for (String table : target.tables) {
            if (Thread.currentThread().isInterrupted()) return;
            cleanTable(table, cutoff);
        }
    }

    private void cleanTable(String table, LocalDateTime cutoff) {
        String deleteSql = "DELETE FROM " + table
                + " WHERE status = 'PUBLISHED' AND published_at < ? ORDER BY published_at, id LIMIT ?";
        int count;
        long total = 0;
        do {
            count = jdbc.update(deleteSql, cutoff, BATCH_SIZE);
            total += count;
        } while (count == BATCH_SIZE && !Thread.currentThread().isInterrupted());
        if (total > 0) log.info("발행 완료 outbox 정리: {} {}건", table, total);
    }
}
