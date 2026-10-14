package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositReconciliationJobStorePort;
import com.amateur.howmuchbackend.domain.settlement.SettlementDepositReconciliationJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementDepositReconciliationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementDepositReconciliationJdbcAdapter implements SettlementDepositReconciliationJobStorePort {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public Optional<SettlementDepositReconciliationJob> schedule(UUID id, LocalDateTime now,
            LocalDateTime next, LocalDateTime expires, LocalDateTime replaceBefore) {
        jdbc.update("""
                UPDATE settlement_deposit_reconciliation_jobs
                SET status='EXHAUSTED', next_attempt_at=NULL, lease_token=NULL, lease_until=NULL,
                    completed_at=?, last_failure_code='WINDOW_EXPIRED', updated_at=GREATEST(updated_at, ?)
                WHERE settlement_id=? AND status IN ('PENDING','RETRY','PROCESSING') AND expires_at <= ?
                """, now, now, id, now);
        return jdbc.query("""
                INSERT INTO settlement_deposit_reconciliation_jobs AS job
                    (settlement_id, next_attempt_at, first_signaled_at, last_signaled_at,
                     expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (settlement_id) DO UPDATE SET
                    generation = CASE WHEN job.status IN ('PENDING','RETRY','PROCESSING')
                        THEN job.generation ELSE job.generation + 1 END,
                    status = CASE WHEN job.status IN ('PENDING','RETRY','PROCESSING')
                        THEN job.status ELSE 'PENDING' END,
                    attempt_count = CASE WHEN job.status IN ('PENDING','RETRY','PROCESSING')
                        THEN job.attempt_count ELSE 0 END,
                    max_attempts = CASE WHEN job.status IN ('PENDING','RETRY','PROCESSING')
                        THEN job.max_attempts ELSE 3 END,
                    next_attempt_at = CASE WHEN job.status IN ('PENDING','RETRY','PROCESSING')
                        THEN job.next_attempt_at ELSE EXCLUDED.next_attempt_at END,
                    lease_token = CASE WHEN job.status='PROCESSING' THEN job.lease_token ELSE NULL END,
                    lease_until = CASE WHEN job.status='PROCESSING' THEN job.lease_until ELSE NULL END,
                    completed_at = NULL,
                    last_failure_code = CASE WHEN job.status IN ('RETRY','PROCESSING')
                        THEN job.last_failure_code ELSE NULL END,
                    first_signaled_at = CASE WHEN job.status IN ('PENDING','RETRY','PROCESSING')
                        THEN job.first_signaled_at ELSE EXCLUDED.first_signaled_at END,
                    last_signaled_at = GREATEST(job.last_signaled_at, EXCLUDED.last_signaled_at),
                    expires_at = CASE WHEN job.status IN ('PENDING','RETRY','PROCESSING')
                        THEN job.expires_at ELSE EXCLUDED.expires_at END,
                    updated_at = GREATEST(job.updated_at, EXCLUDED.updated_at)
                WHERE job.status IN ('PENDING','RETRY','PROCESSING') OR job.first_signaled_at <= ?
                RETURNING *
                """, this::map, id, next, now, now, expires, now, now, replaceBefore).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<SettlementDepositReconciliationJob> claimDue(LocalDateTime now, LocalDateTime leaseUntil) {
        jdbc.update("""
                UPDATE settlement_deposit_reconciliation_jobs SET
                    status=CASE WHEN attempt_count >= max_attempts OR expires_at <= ?
                        THEN 'EXHAUSTED' ELSE 'RETRY' END,
                    next_attempt_at=CASE WHEN attempt_count >= max_attempts OR expires_at <= ?
                        THEN NULL ELSE ? END,
                    completed_at=CASE WHEN attempt_count >= max_attempts OR expires_at <= ?
                        THEN ? ELSE NULL END,
                    lease_token=NULL, lease_until=NULL,
                    last_failure_code=CASE WHEN expires_at <= ? THEN 'WINDOW_EXPIRED' ELSE 'PROCESSING_TIMEOUT' END,
                    updated_at=GREATEST(updated_at, ?)
                WHERE status='PROCESSING' AND (lease_until <= ? OR expires_at <= ?)
                """, now, now, now, now, now, now, now, now, now);
        jdbc.update("""
                UPDATE settlement_deposit_reconciliation_jobs
                SET status='EXHAUSTED', next_attempt_at=NULL, completed_at=?,
                    last_failure_code='WINDOW_EXPIRED', updated_at=GREATEST(updated_at, ?)
                WHERE status IN ('PENDING','RETRY') AND expires_at <= ?
                """, now, now, now);
        return jdbc.query("""
                WITH due AS (
                    SELECT settlement_id FROM settlement_deposit_reconciliation_jobs
                    WHERE status IN ('PENDING','RETRY') AND next_attempt_at <= ? AND expires_at > ?
                    ORDER BY next_attempt_at, settlement_id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE settlement_deposit_reconciliation_jobs AS job
                SET status='PROCESSING', attempt_count=attempt_count+1,
                    next_attempt_at=NULL, lease_token=?, lease_until=?, updated_at=GREATEST(updated_at, ?)
                FROM due WHERE job.settlement_id=due.settlement_id RETURNING job.*
                """, this::map, now, now, UUID.randomUUID(), leaseUntil, now).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean renew(SettlementDepositReconciliationJob claim, LocalDateTime now, LocalDateTime until) {
        return jdbc.update("""
                UPDATE settlement_deposit_reconciliation_jobs SET lease_until=?, updated_at=GREATEST(updated_at, ?)
                WHERE settlement_id=? AND generation=? AND status='PROCESSING' AND lease_token=?
                    AND lease_until > ? AND expires_at > ?
                """, until, now, claim.settlementId(), claim.generation(), claim.leaseToken(), now, now) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockCurrentClaim(SettlementDepositReconciliationJob claim, LocalDateTime now) {
        return !jdbc.query("""
                SELECT settlement_id FROM settlement_deposit_reconciliation_jobs
                WHERE settlement_id=? AND generation=? AND status='PROCESSING' AND lease_token=?
                    AND lease_until > ? AND expires_at > ? FOR UPDATE
                """, (row, n) -> row.getObject(1, UUID.class), claim.settlementId(), claim.generation(),
                claim.leaseToken(), now, now).isEmpty();
    }

    @Override
    @Transactional
    public boolean finish(SettlementDepositReconciliationJob claim, SettlementDepositReconciliationStatus status,
            LocalDateTime next, String failure, LocalDateTime now) {
        return jdbc.update("""
                UPDATE settlement_deposit_reconciliation_jobs SET status=?, next_attempt_at=?,
                    lease_token=NULL, lease_until=NULL, completed_at=?, last_failure_code=?,
                    updated_at=GREATEST(updated_at, ?)
                WHERE settlement_id=? AND generation=? AND status='PROCESSING' AND lease_token=?
                    AND lease_until > ? AND expires_at > ?
                """, status.name(), next, status.active() ? null : now, failure, now,
                claim.settlementId(), claim.generation(), claim.leaseToken(), now, now) == 1;
    }

    private SettlementDepositReconciliationJob map(ResultSet row, int index) throws SQLException {
        return new SettlementDepositReconciliationJob(row.getObject("settlement_id", UUID.class),
                row.getLong("generation"), SettlementDepositReconciliationStatus.valueOf(row.getString("status")),
                row.getInt("attempt_count"), row.getInt("max_attempts"), time(row, "next_attempt_at"),
                row.getObject("lease_token", UUID.class), time(row, "lease_until"),
                time(row, "first_signaled_at"), time(row, "expires_at"));
    }
    private LocalDateTime time(ResultSet row, String field) throws SQLException {
        var timestamp = row.getTimestamp(field);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
