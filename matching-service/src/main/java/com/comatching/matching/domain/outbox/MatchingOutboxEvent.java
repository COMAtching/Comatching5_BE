package com.comatching.matching.domain.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "matching_outbox_events",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_matching_outbox_matching_id",
                        columnNames = "matching_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_matching_outbox_pending",
                        columnList = "status, next_attempt_at, id"
                ),
                @Index(
                        name = "idx_matching_outbox_processing",
                        columnList = "status, processing_until, id"
                ),
                @Index(name = "idx_matching_outbox_cleanup", columnList = "status, published_at, id")
        }
)
public class MatchingOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "matching_id", nullable = false)
    private Long matchingId;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private MatchingOutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "processing_token", length = 36)
    private String processingToken;

    @Column(name = "processing_until")
    private LocalDateTime processingUntil;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public static MatchingOutboxEvent pending(
            Long matchingId,
            String payload
    ) {
        LocalDateTime now = LocalDateTime.now();

        MatchingOutboxEvent event = new MatchingOutboxEvent();
        event.matchingId = matchingId;
        event.payload = payload;
        event.status = MatchingOutboxStatus.PENDING;
        event.retryCount = 0;
        event.nextAttemptAt = now;
        event.createdAt = now;

        return event;
    }
}