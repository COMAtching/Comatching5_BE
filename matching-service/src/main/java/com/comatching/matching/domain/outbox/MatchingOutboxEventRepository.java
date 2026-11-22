package com.comatching.matching.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MatchingOutboxEventRepository extends JpaRepository<MatchingOutboxEvent, Long> {

    // 조회만으로 소유권을 얻지는 않는다. 아래 claim()에 성공해야 발행할 수 있다.
    // 처리 중 서버가 종료된 이벤트는 processing_until 이후 다시 대상이 된다.
    @Query(value = """
            SELECT id
            FROM matching_outbox_events
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'PROCESSING' AND processing_until <= :now)
            ORDER BY id
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findReadyIds(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    // 조건부 UPDATE: 같은 행을 동시에 선점해도 한 발행기만 1을 반환받는다.
    // 수정 쿼리는 Dispatcher의 짧은 DB 트랜잭션 안에서 호출한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE matching_outbox_events
            SET status = 'PROCESSING',
                processing_token = :token,
                processing_until = :until
            WHERE id = :id
              AND (
                  (status = 'PENDING' AND next_attempt_at <= :now)
                  OR (status = 'PROCESSING' AND processing_until <= :now)
              )
            """, nativeQuery = true)
    int claim(
            @Param("id") Long id,
            @Param("token") String token,
            @Param("now") LocalDateTime now,
            @Param("until") LocalDateTime until
    );

    // 만료 뒤 다른 발행기가 선점했다면 token이 달라지므로 0을 반환한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE matching_outbox_events
            SET status = 'PUBLISHED',
                published_at = :now,
                processing_token = NULL,
                processing_until = NULL,
                last_error = NULL
            WHERE id = :id
              AND status = 'PROCESSING'
              AND processing_token = :token
            """, nativeQuery = true)
    int markPublished(
            @Param("id") Long id,
            @Param("token") String token,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE matching_outbox_events
            SET status = 'PENDING',
                retry_count = retry_count + 1,
                next_attempt_at = :nextAttemptAt,
                last_error = :error,
                processing_token = NULL,
                processing_until = NULL
            WHERE id = :id
              AND status = 'PROCESSING'
              AND processing_token = :token
            """, nativeQuery = true)
    int markRetry(
            @Param("id") Long id,
            @Param("token") String token,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("error") String error
    );
}
