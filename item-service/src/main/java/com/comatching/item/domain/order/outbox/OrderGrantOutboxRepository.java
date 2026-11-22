package com.comatching.item.domain.order.outbox;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.common.dto.event.order.RefundCompleted;
import com.comatching.item.domain.order.entity.Order;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class OrderGrantOutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    record Entry(long id, long orderId, String topic, String payload, int retryCount) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Order order, LocalDateTime grantedAt) {
        var items = order.getOrderItems().stream()
                .map(line -> new ItemsGranted.GrantedItem(line.getItemType(), line.getQuantity()))
                .toList();
        var event = new ItemsGranted("items-granted:" + order.getId(), order.getId(), order.getMemberId(),
                grantedAt, items);
        insert(order.getId(), ItemsGranted.TOPIC, event, grantedAt);
    }

    public boolean hasGrantEvent(long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM order_grant_outbox_events WHERE order_id=? AND topic=?)
                """, Boolean.class, orderId, ItemsGranted.TOPIC));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueRefund(Order order) {
        var event = new RefundCompleted("refund-completed:" + order.getId(), order.getId(), order.getMemberId(),
                order.getExpectedPrice(), order.getRefundCompletedAt());
        insert(event.orderId(), RefundCompleted.TOPIC, event, event.completedAt());
    }

    // 한 주문은 지급 또는 미지급 전액 환불 중 하나로만 종료된다. UNIQUE(order_id)를 공유한다.
    private void insert(long orderId, String topic, Object event, LocalDateTime occurredAt) {
        try {
            jdbc.update("""
                    INSERT INTO order_grant_outbox_events (order_id, topic, payload, status, available_at, created_at)
                    VALUES (?, ?, ?, 'PENDING', ?, ?)
                    """, orderId, topic, objectMapper.writeValueAsString(event), occurredAt, occurredAt);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("주문 이벤트 직렬화 실패. orderId=" + orderId, e);
        }
    }

    List<Entry> ready() {
        return jdbc.query("""
                SELECT id, order_id, topic, payload, retry_count FROM order_grant_outbox_events
                WHERE status IN ('PENDING', 'PROCESSING') AND available_at <= ?
                ORDER BY id LIMIT 50
                """, (rs, n) -> new Entry(rs.getLong("id"), rs.getLong("order_id"), rs.getString("topic"),
                rs.getString("payload"), rs.getInt("retry_count")), LocalDateTime.now());
    }

    // PROCESSING 중 서버가 종료되면 다음 워커가 기한이 지난 행을 새 토큰으로 선점한다.
    boolean claim(long id, String token) {
        var now = LocalDateTime.now();
        return jdbc.update("""
                UPDATE order_grant_outbox_events SET status = 'PROCESSING', processing_token = ?, available_at = ?
                WHERE id = ? AND status IN ('PENDING', 'PROCESSING') AND available_at <= ?
                """, token, now.plusMinutes(2), id, now) == 1;
    }

    boolean published(long id, String token) {
        return jdbc.update("""
                UPDATE order_grant_outbox_events SET status = 'PUBLISHED', published_at = ?, processing_token = NULL
                WHERE id = ? AND status = 'PROCESSING' AND processing_token = ?
                """, LocalDateTime.now(), id, token) == 1;
    }

    void retry(Entry entry, String token) {
        long seconds = Math.min(60, 1L << Math.min(entry.retryCount(), 6));
        jdbc.update("""
                UPDATE order_grant_outbox_events SET status = 'PENDING', available_at = ?, processing_token = NULL,
                    retry_count = retry_count + 1
                WHERE id = ? AND status = 'PROCESSING' AND processing_token = ?
                """, LocalDateTime.now().plusSeconds(seconds), entry.id(), token);
    }
}
