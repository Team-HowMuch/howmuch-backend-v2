package com.amateur.howmuchbackend.adapter.out.persistence.recap;

import com.amateur.howmuchbackend.application.port.out.recap.RecapPublicationStorePort;
import com.amateur.howmuchbackend.application.recap.model.RecapPublicationClaim;
import com.amateur.howmuchbackend.application.recap.model.RecapPublicationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RecapPublicationPersistenceAdapter implements RecapPublicationStorePort {
    private static final String OWNED = "recap_id=:id AND status='PROCESSING' AND lease_token=:token AND lease_until>:now";
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID recapId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO recap_publication_outbox(recap_id, status, next_attempt_at, created_at, updated_at)
                VALUES (:id, 'PENDING', :now, :now, :now) ON CONFLICT (recap_id) DO NOTHING
                """, new MapSqlParameterSource("id", recapId).addValue("now", Timestamp.valueOf(now)));
    }

    @Override
    @Transactional
    public int recoverExpired(LocalDateTime now, int limit) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT recap_id FROM recap_publication_outbox
                    WHERE status='PROCESSING' AND lease_until<=:now
                    ORDER BY lease_until, recap_id LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                UPDATE recap_publication_outbox event SET status='PENDING', next_attempt_at=:now,
                    lease_token=NULL, lease_until=NULL, last_failure_code='LEASE_EXPIRED', updated_at=:now
                FROM expired WHERE event.recap_id=expired.recap_id
                """, new MapSqlParameterSource("now", Timestamp.valueOf(now)).addValue("limit", limit));
    }

    @Override
    @Transactional
    public Optional<RecapPublicationClaim> claimNext(LocalDateTime now, LocalDateTime leaseUntil) {
        if (!leaseUntil.isAfter(now)) throw new IllegalArgumentException("Publication lease must expire in the future.");
        var params = new MapSqlParameterSource("now", Timestamp.valueOf(now))
                .addValue("until", Timestamp.valueOf(leaseUntil)).addValue("token", UUID.randomUUID());
        return jdbc.query("""
                WITH candidate AS (
                    SELECT event.recap_id, recap.owner_user_id, recap.month_start,
                           recap.completed_settlement_count, recap.generated_at
                    FROM recap_publication_outbox event
                    JOIN monthly_recaps recap ON recap.id=event.recap_id
                    JOIN users owner ON owner.id=recap.owner_user_id AND owner.active=TRUE
                    WHERE event.status='PENDING' AND event.next_attempt_at<=:now
                    ORDER BY event.next_attempt_at, event.created_at, event.recap_id
                    LIMIT 1 FOR UPDATE OF event SKIP LOCKED
                )
                UPDATE recap_publication_outbox event SET status='PROCESSING',
                    attempt_count=attempt_count+1, next_attempt_at=NULL,
                    lease_token=:token, lease_until=:until, updated_at=:now
                FROM candidate WHERE event.recap_id=candidate.recap_id
                RETURNING event.recap_id, event.lease_token, event.attempt_count,
                    candidate.owner_user_id, candidate.month_start,
                    candidate.completed_settlement_count, candidate.generated_at
                """, params, (row, index) -> new RecapPublicationClaim(
                new RecapPublicationMessage(row.getObject("recap_id", UUID.class), row.getLong("owner_user_id"),
                        YearMonth.from(row.getDate("month_start").toLocalDate()), row.getInt("completed_settlement_count"),
                        row.getTimestamp("generated_at").toLocalDateTime()),
                row.getObject("lease_token", UUID.class), row.getInt("attempt_count"))).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean published(RecapPublicationClaim claim, LocalDateTime now) {
        return jdbc.update("UPDATE recap_publication_outbox SET status='PUBLISHED', published_at=:now, "
                + "lease_token=NULL, lease_until=NULL, last_failure_code=NULL, updated_at=:now WHERE " + OWNED,
                params(claim, now)) == 1;
    }

    @Override
    @Transactional
    public boolean retry(RecapPublicationClaim claim, LocalDateTime now, LocalDateTime nextAttemptAt) {
        if (!nextAttemptAt.isAfter(now)) throw new IllegalArgumentException("Publication retry must be in the future.");
        return jdbc.update("UPDATE recap_publication_outbox SET status='PENDING', next_attempt_at=:next, "
                + "lease_token=NULL, lease_until=NULL, last_failure_code='PUBLICATION_FAILED', updated_at=:now WHERE " + OWNED,
                params(claim, now).addValue("next", Timestamp.valueOf(nextAttemptAt))) == 1;
    }

    private MapSqlParameterSource params(RecapPublicationClaim claim, LocalDateTime now) {
        return new MapSqlParameterSource("id", claim.message().messageId()).addValue("token", claim.leaseToken())
                .addValue("now", Timestamp.valueOf(now));
    }
}
