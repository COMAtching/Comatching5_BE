package com.comatching.user.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import com.comatching.user.support.OutboxMySqlSupport;

@Tag("integration")
class PublishedOutboxCleanupIT extends OutboxMySqlSupport {
    @Configuration
    @EnableScheduling
    @ComponentScan("com.comatching.common.outbox")
    static class CleanupConfig {}

    @ParameterizedTest
    @CsvSource({"MEMBER,member_outbox_events,PENDING", "MATCHING,matching_outbox_events,PENDING",
            "ORDER,order_outbox_events,NEW", "ORDER,order_grant_outbox_events,PENDING"})
    void purgesOnlyRowsPublishedMoreThanSevenDaysAgo(String target, String table, String pending) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        if (target.equals("ORDER")) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS order_outbox_events LIKE member_outbox_events");
            jdbc.execute("CREATE TABLE IF NOT EXISTS order_grant_outbox_events LIKE member_outbox_events");
        } else if (target.equals("MATCHING")) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS matching_outbox_events LIKE member_outbox_events");
        }
        jdbc.update("DELETE FROM " + table + " WHERE member_id=9000");
        LocalDateTime now = LocalDateTime.now();
        String insert = "INSERT INTO " + table + " (member_id, topic, payload, status, available_at, created_at, published_at)"
                + " VALUES (9000, 'probe', '{}', ?, ?, ?, ?)";
        jdbc.batchUpdate(insert, java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> new Object[]{"PUBLISHED", now, now.minusDays(30), now.minusDays(8)}).toList());
        jdbc.update(insert, "PUBLISHED", now, now.minusDays(30), now.minusDays(6));
        jdbc.update(insert, pending, now, now.minusDays(30), now.minusDays(8)); // 상태 조건도 검사한다.
        jdbc.update(insert, "PROCESSING", now, now.minusDays(30), null);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "comatching.outbox.cleanup-target", target, "comatching.outbox.cleanup-cron", "0 0 0 29 2 *")));
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.register(CleanupConfig.class);
            context.refresh();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).singleElement()
                    .satisfies(task -> task.getTask().getRunnable().run());
            assertThat(jdbc.queryForList("SELECT status FROM " + table + " WHERE member_id=9000 ORDER BY id", String.class))
                    .containsExactly("PUBLISHED", pending, "PROCESSING");
        } finally {
            jdbc.update("DELETE FROM " + table + " WHERE member_id=9000");
        }
    }
}
