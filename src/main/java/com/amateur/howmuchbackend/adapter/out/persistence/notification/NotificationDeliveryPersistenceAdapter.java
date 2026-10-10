package com.amateur.howmuchbackend.adapter.out.persistence.notification;

import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryPublisherPort.DeliveryMessage;
import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryStorePort;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NotificationDeliveryPersistenceAdapter implements NotificationDeliveryStorePort {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    @Transactional
    public Optional<PublicationClaim> claimPublication(LocalDateTime now, LocalDateTime leaseUntil,
            LocalDateTime republishBefore) {
        UUID token = UUID.randomUUID();
        var rows = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM settlement_notification_outbox
                     WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now
                       AND ((publication_status = 'PENDING' AND COALESCE(next_publication_at, next_attempt_at) <= :now)
                         OR (publication_status = 'PROCESSING' AND publication_lease_until <= :now)
                         OR (publication_status = 'PUBLISHED' AND published_at <= :republishBefore))
                     ORDER BY COALESCE(next_publication_at, next_attempt_at), created_at, id
                     LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE settlement_notification_outbox event
                   SET publication_status = 'PROCESSING', publication_attempt_count = publication_attempt_count + 1,
                       publication_lease_token = :token, publication_lease_until = :leaseUntil,
                       next_publication_at = NULL, published_at = NULL, updated_at = :now
                  FROM candidate WHERE event.id = candidate.id
                RETURNING event.*
                """, new MapSqlParameterSource().addValue("now", now).addValue("leaseUntil", leaseUntil)
                .addValue("republishBefore", republishBefore).addValue("token", token), (rs, rowNum) ->
                new PublicationClaim(new DeliveryMessage(uuid(rs, "delivery_message_id"), uuid(rs, "id"),
                        rs.getLong("delivery_generation"), rs.getLong("recipient_user_id"), time(rs, "created_at")),
                        token, rs.getInt("publication_attempt_count")));
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public boolean publicationSucceeded(PublicationClaim claim, LocalDateTime now) {
        return jdbc.update("""
                UPDATE settlement_notification_outbox
                   SET publication_status = 'PUBLISHED', published_at = :now,
                       publication_lease_token = NULL, publication_lease_until = NULL, updated_at = :now
                 WHERE id = :id AND delivery_generation = :generation
                   AND publication_status = 'PROCESSING' AND publication_lease_token = :token
                """, publicationParameters(claim).addValue("now", now)) == 1;
    }

    @Override
    @Transactional
    public boolean publicationFailed(PublicationClaim claim, LocalDateTime retryAt, LocalDateTime now) {
        return jdbc.update("""
                UPDATE settlement_notification_outbox
                   SET publication_status = 'PENDING', next_publication_at = :retryAt, published_at = NULL,
                       publication_lease_token = NULL, publication_lease_until = NULL, updated_at = :now
                 WHERE id = :id AND delivery_generation = :generation
                   AND publication_status = 'PROCESSING' AND publication_lease_token = :token
                """, publicationParameters(claim).addValue("now", now).addValue("retryAt", retryAt)) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeliveryMessage> findDeliveryMessage(UUID deliveryId, long generation) {
        return jdbc.query("""
                SELECT id, delivery_message_id, delivery_generation, recipient_user_id, created_at
                  FROM settlement_notification_outbox WHERE id = :id AND delivery_generation = :generation
                """, new MapSqlParameterSource().addValue("id", deliveryId).addValue("generation", generation),
                (rs, rowNum) -> new DeliveryMessage(uuid(rs, "delivery_message_id"), uuid(rs, "id"),
                        rs.getLong("delivery_generation"), rs.getLong("recipient_user_id"), time(rs, "created_at"))).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SettlementNotificationOutbox> findReadyDelivery(UUID deliveryId, long generation, LocalDateTime now) {
        return jdbc.query("""
                SELECT * FROM settlement_notification_outbox
                 WHERE id = :id AND delivery_generation = :generation
                   AND status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now
                """, new MapSqlParameterSource().addValue("id", deliveryId).addValue("generation", generation)
                .addValue("now", now), (rs, rowNum) -> event(rs)).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean deferReadyDelivery(UUID deliveryId, long generation, LocalDateTime resumeAt, LocalDateTime now) {
        if (!resumeAt.isAfter(now)) throw new IllegalArgumentException("Deferred delivery must resume in the future.");
        return jdbc.update("""
                UPDATE settlement_notification_outbox
                   SET next_attempt_at = :resumeAt, delivery_generation = delivery_generation + 1,
                       delivery_message_id = :messageId, publication_status = 'PENDING', publication_attempt_count = 0,
                       next_publication_at = :resumeAt, publication_lease_token = NULL,
                       publication_lease_until = NULL, published_at = NULL, updated_at = :now
                 WHERE id = :id AND delivery_generation = :generation
                   AND status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now
                """, new MapSqlParameterSource().addValue("id", deliveryId).addValue("generation", generation)
                .addValue("resumeAt", resumeAt).addValue("now", now).addValue("messageId", UUID.randomUUID())) == 1;
    }

    @Override
    @Transactional
    public Optional<DeliveryClaim> claimDelivery(UUID deliveryId, long generation, LocalDateTime now) {
        var rows = jdbc.query("""
                UPDATE settlement_notification_outbox
                   SET status = 'PROCESSING', attempt_count = attempt_count + 1, next_attempt_at = NULL,
                       processing_started_at = :now, updated_at = :now
                 WHERE id = :id AND delivery_generation = :generation
                   AND status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now
                RETURNING *
                """, new MapSqlParameterSource().addValue("id", deliveryId).addValue("generation", generation)
                .addValue("now", now), (rs, rowNum) -> new DeliveryClaim(event(rs), rs.getLong("delivery_generation")));
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public boolean completeDelivery(DeliveryClaim claim, SettlementNotificationOutbox result, LocalDateTime now) {
        if (!claim.event().id().equals(result.id()) || claim.event().attemptCount() != result.attemptCount())
            throw new IllegalArgumentException("Delivery identity and attempt cannot change.");
        if (result.status() != SettlementNotificationOutboxStatus.SUCCEEDED
                && result.status() != SettlementNotificationOutboxStatus.FAILED
                && result.status() != SettlementNotificationOutboxStatus.RETRY)
            throw new IllegalArgumentException("A completed delivery outcome is required.");
        boolean retry = result.status() == SettlementNotificationOutboxStatus.RETRY;
        String publicationReset = retry ? """
                , delivery_generation = delivery_generation + 1, delivery_message_id = :messageId,
                  publication_status = 'PENDING', publication_attempt_count = 0, next_publication_at = :nextAt,
                  publication_lease_token = NULL, publication_lease_until = NULL, published_at = NULL
                """ : "";
        return jdbc.update("""
                UPDATE settlement_notification_outbox
                   SET status = :status, next_attempt_at = :nextAt, processing_started_at = NULL,
                       completed_at = :completedAt, last_failure_code = :failure, updated_at = :now
                """ + publicationReset + """
                 WHERE id = :id AND delivery_generation = :generation AND status = 'PROCESSING'
                   AND attempt_count = :attempt
                """, new MapSqlParameterSource().addValue("id", result.id()).addValue("generation", claim.generation())
                .addValue("attempt", result.attemptCount()).addValue("status", result.status().name())
                .addValue("nextAt", result.nextAttemptAt()).addValue("completedAt", result.completedAt())
                .addValue("failure", result.lastFailureCode() == null ? null : result.lastFailureCode().name())
                .addValue("now", now).addValue("messageId", UUID.randomUUID())) == 1;
    }

    @Override
    @Transactional
    public int recoverStaleDeliveries(int batchSize, LocalDateTime staleBefore, LocalDateTime now, int maxAttempts) {
        if (batchSize < 1 || maxAttempts < 1) throw new IllegalArgumentException("Positive recovery limits are required.");
        return jdbc.update("""
                WITH candidates AS (
                    SELECT id FROM settlement_notification_outbox
                     WHERE status = 'PROCESSING' AND processing_started_at <= :staleBefore
                     ORDER BY processing_started_at, id LIMIT :batchSize FOR UPDATE SKIP LOCKED
                )
                UPDATE settlement_notification_outbox event
                   SET status = CASE WHEN attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'RETRY' END,
                       next_attempt_at = CASE WHEN attempt_count >= :maxAttempts THEN NULL ELSE :now END,
                       processing_started_at = NULL,
                       completed_at = CASE WHEN attempt_count >= :maxAttempts THEN :now ELSE NULL END,
                       last_failure_code = 'PROCESSING_TIMEOUT', updated_at = :now,
                       delivery_generation = CASE WHEN attempt_count >= :maxAttempts THEN delivery_generation ELSE delivery_generation + 1 END,
                       delivery_message_id = CASE WHEN attempt_count >= :maxAttempts THEN delivery_message_id ELSE gen_random_uuid() END,
                       publication_status = CASE WHEN attempt_count >= :maxAttempts THEN publication_status ELSE 'PENDING' END,
                       publication_attempt_count = CASE WHEN attempt_count >= :maxAttempts THEN publication_attempt_count ELSE 0 END,
                       next_publication_at = CASE WHEN attempt_count >= :maxAttempts THEN next_publication_at ELSE :now END,
                       publication_lease_token = CASE WHEN attempt_count >= :maxAttempts THEN publication_lease_token ELSE NULL END,
                       publication_lease_until = CASE WHEN attempt_count >= :maxAttempts THEN publication_lease_until ELSE NULL END,
                       published_at = CASE WHEN attempt_count >= :maxAttempts THEN published_at ELSE NULL END
                  FROM candidates WHERE event.id = candidates.id
                """, new MapSqlParameterSource().addValue("now", now).addValue("staleBefore", staleBefore)
                .addValue("batchSize", batchSize).addValue("maxAttempts", maxAttempts));
    }

    private MapSqlParameterSource publicationParameters(PublicationClaim claim) {
        return new MapSqlParameterSource().addValue("id", claim.message().deliveryId())
                .addValue("generation", claim.message().generation()).addValue("token", claim.leaseToken());
    }
    private static SettlementNotificationOutbox event(ResultSet rs) throws SQLException {
        String failure = rs.getString("last_failure_code");
        return new SettlementNotificationOutbox(uuid(rs, "id"), uuid(rs, "settlement_id"), rs.getLong("recipient_user_id"),
                SettlementNotificationOutboxStatus.valueOf(rs.getString("status")), rs.getInt("attempt_count"),
                time(rs, "next_attempt_at"), time(rs, "processing_started_at"), time(rs, "completed_at"),
                failure == null ? null : SettlementNotificationFailureCode.valueOf(failure), uuid(rs, "notification_id"));
    }
    private static UUID uuid(ResultSet rs, String field) throws SQLException { return rs.getObject(field, UUID.class); }
    private static LocalDateTime time(ResultSet rs, String field) throws SQLException {
        var timestamp = rs.getTimestamp(field);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
