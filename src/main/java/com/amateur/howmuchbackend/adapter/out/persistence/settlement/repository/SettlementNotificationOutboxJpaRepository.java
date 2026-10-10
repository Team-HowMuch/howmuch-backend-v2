package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementNotificationOutboxJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementNotificationOutboxJpaRepository
        extends JpaRepository<SettlementNotificationOutboxJpaEntity, UUID> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE settlement_notification_outbox
               SET status = CASE WHEN attempt_count = 1 THEN 'PENDING' ELSE 'RETRY' END,
                   attempt_count = attempt_count - 1, next_attempt_at = :resumeAt,
                   processing_started_at = NULL, completed_at = NULL,
                   last_failure_code = CASE WHEN attempt_count = 1 THEN NULL ELSE last_failure_code END,
                   delivery_generation = delivery_generation + 1, delivery_message_id = gen_random_uuid(),
                   publication_status = 'PENDING', publication_attempt_count = 0, next_publication_at = :resumeAt,
                   publication_lease_token = NULL, publication_lease_until = NULL, published_at = NULL, updated_at = :now
             WHERE id = :id AND status = 'PROCESSING' AND attempt_count = :attempt
            """, nativeQuery = true)
    int deferProcessing(@Param("id") UUID id, @Param("attempt") int attempt,
            @Param("resumeAt") LocalDateTime resumeAt, @Param("now") LocalDateTime now);
    @Query(value = """
            SELECT event.*
              FROM settlement_notification_outbox event
              LEFT JOIN settlements settlement ON settlement.id = event.settlement_id
             WHERE event.status IN ('PENDING', 'RETRY')
               AND event.next_attempt_at <= :now
               AND (event.settlement_id IS NULL OR settlement.deleted_at IS NULL)
             ORDER BY event.next_attempt_at, event.created_at, event.id
             LIMIT 1
               FOR UPDATE OF event SKIP LOCKED
            """, nativeQuery = true)
    Optional<SettlementNotificationOutboxJpaEntity> findNextReadyForUpdate(
            @Param("now") LocalDateTime now
    );

    @Query(value = """
            SELECT event.*
              FROM settlement_notification_outbox event
             WHERE event.status = 'PROCESSING'
               AND event.processing_started_at <= :staleBefore
             ORDER BY event.processing_started_at, event.id
             LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<SettlementNotificationOutboxJpaEntity> findStaleForUpdate(
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("batchSize") int batchSize
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE settlement_notification_outbox
               SET status = 'SUCCEEDED',
                   next_attempt_at = NULL,
                   processing_started_at = NULL,
                   completed_at = :completedAt,
                   last_failure_code = NULL,
                   updated_at = :completedAt
             WHERE id = :id
               AND status = 'PROCESSING'
               AND attempt_count = :attemptCount
            """, nativeQuery = true)
    int markSucceeded(
            @Param("id") UUID id,
            @Param("attemptCount") int attemptCount,
            @Param("completedAt") LocalDateTime completedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE settlement_notification_outbox
               SET status = 'RETRY',
                   next_attempt_at = :nextAttemptAt,
                   processing_started_at = NULL,
                   completed_at = NULL,
                   last_failure_code = :failureCode,
                   delivery_generation = delivery_generation + 1,
                   delivery_message_id = gen_random_uuid(),
                   publication_status = 'PENDING', publication_attempt_count = 0,
                   next_publication_at = :nextAttemptAt, publication_lease_token = NULL,
                   publication_lease_until = NULL, published_at = NULL,
                   updated_at = :updatedAt
             WHERE id = :id
               AND status = 'PROCESSING'
               AND attempt_count = :attemptCount
            """, nativeQuery = true)
    int markRetry(
            @Param("id") UUID id,
            @Param("attemptCount") int attemptCount,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("failureCode") String failureCode,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE settlement_notification_outbox
               SET status = 'FAILED',
                   next_attempt_at = NULL,
                   processing_started_at = NULL,
                   completed_at = :completedAt,
                   last_failure_code = :failureCode,
                   updated_at = :completedAt
             WHERE id = :id
               AND status = 'PROCESSING'
               AND attempt_count = :attemptCount
            """, nativeQuery = true)
    int markFailed(
            @Param("id") UUID id,
            @Param("attemptCount") int attemptCount,
            @Param("failureCode") String failureCode,
            @Param("completedAt") LocalDateTime completedAt
    );
}
