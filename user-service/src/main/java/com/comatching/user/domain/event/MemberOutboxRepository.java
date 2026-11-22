package com.comatching.user.domain.event;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MemberOutboxRepository {
    private final JdbcTemplate jdbc;

    record Entry(long id, long memberId, String topic, String payload, int retryCount) {}

    public void insert(String topic, long memberId, String payload) {
        var now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO member_outbox_events
                    (member_id, topic, payload, status, available_at, created_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?)
                """, memberId, topic, payload, now, now);
    }

    // 앞선 미완료 행이 보이면 같은 회원·토픽의 다음 이벤트는 기다린다.
    List<Entry> ready() {
        return jdbc.query("""
                SELECT e.id, e.member_id, e.topic, e.payload, e.retry_count
                FROM member_outbox_events e
                WHERE e.status IN ('PENDING', 'PROCESSING') AND e.available_at <= ?
                  AND NOT EXISTS (
                    SELECT 1 FROM member_outbox_events prev
                    WHERE prev.member_id = e.member_id AND prev.topic = e.topic
                      AND prev.id < e.id AND prev.status IN ('PENDING', 'PROCESSING')
                  )
                ORDER BY e.id LIMIT 50
                """, (rs, n) -> new Entry(rs.getLong("id"), rs.getLong("member_id"),
                        rs.getString("topic"), rs.getString("payload"), rs.getInt("retry_count")),
                LocalDateTime.now());
    }

    boolean claim(long id, String token) {
        var now = LocalDateTime.now();
        return jdbc.update("""
                UPDATE member_outbox_events SET status = 'PROCESSING', processing_token = ?, available_at = ?
                WHERE id = ? AND status IN ('PENDING', 'PROCESSING') AND available_at <= ?
                """, token, now.plusMinutes(2), id, now) == 1;
    }

    boolean published(long id, String token) {
        return jdbc.update("""
                UPDATE member_outbox_events SET status = 'PUBLISHED', published_at = ?, processing_token = NULL
                WHERE id = ? AND status = 'PROCESSING' AND processing_token = ?
                """, LocalDateTime.now(), id, token) == 1;
    }

    void retry(Entry entry, String token) {
        long seconds = Math.min(60, 1L << Math.min(entry.retryCount(), 6));
        jdbc.update("""
                UPDATE member_outbox_events SET status = 'PENDING', available_at = ?,
                    retry_count = retry_count + 1, processing_token = NULL
                WHERE id = ? AND status = 'PROCESSING' AND processing_token = ?
                """, LocalDateTime.now().plusSeconds(seconds), entry.id(), token);
    }
}
