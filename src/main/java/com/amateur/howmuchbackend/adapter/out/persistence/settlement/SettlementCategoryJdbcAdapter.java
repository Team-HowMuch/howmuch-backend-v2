package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementCategoryJobStorePort;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategory;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategorySource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * settlement_categories 분류 작업(V56). 선점·회수 방식은 입금 대조 작업
 * {@link SettlementDepositReconciliationJdbcAdapter}를 따른다.
 */
@Component
@RequiredArgsConstructor
public class SettlementCategoryJdbcAdapter implements SettlementCategoryJobStorePort {
    // 텍스트 블록 끝의 공백은 잘리므로 앞에 공백을 둔 일반 문자열로 둔다("WHERE" 뒤에 바로 붙는다).
    private static final String OWNED_CLAIM =
            " settlement_id = ? AND status = 'PROCESSING' AND lease_token = ? AND lease_until > ?";

    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void schedule(UUID settlementId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO settlement_categories (settlement_id, status, next_attempt_at, created_at, updated_at)
                VALUES (?, 'PENDING', ?, ?, ?)
                ON CONFLICT (settlement_id) DO NOTHING
                """, settlementId, now, now, now);
    }

    @Override
    @Transactional
    public void saveUncategorized(UUID settlementId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, category_code, source, classified_at, created_at, updated_at
                ) VALUES (?, 'CLASSIFIED', ?, ?, ?, ?, ?)
                ON CONFLICT (settlement_id) DO NOTHING
                """, settlementId, SettlementCategory.UNCATEGORIZED, SettlementCategorySource.UNCATEGORIZED.name(),
                now, now, now);
    }

    @Override
    @Transactional
    public void saveRecurring(UUID settlementId, String categoryCode, SettlementCategorySource source,
            LocalDateTime now) {
        jdbc.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, category_code, source, classified_at, created_at, updated_at
                ) VALUES (?, 'CLASSIFIED', ?, ?, ?, ?, ?)
                ON CONFLICT (settlement_id) DO NOTHING
                """, settlementId, categoryCode, source.name(), now, now, now);
    }

    @Override
    @Transactional
    public Optional<SettlementCategoryJob> claimDue(LocalDateTime now, LocalDateTime leaseUntil) {
        // 만료된 선점은 바로 다시 가져가지 않는다. 시도가 남았으면 재시도로, 다 썼으면 미분류로 끝낸다.
        jdbc.update("""
                UPDATE settlement_categories SET
                    status = CASE WHEN attempt_count >= max_attempts THEN 'CLASSIFIED' ELSE 'RETRY' END,
                    category_code = CASE WHEN attempt_count >= max_attempts THEN ? ELSE NULL END,
                    source = CASE WHEN attempt_count >= max_attempts THEN ? ELSE NULL END,
                    classified_at = CASE WHEN attempt_count >= max_attempts THEN GREATEST(created_at, ?) ELSE NULL END,
                    next_attempt_at = CASE WHEN attempt_count >= max_attempts THEN NULL ELSE ? END,
                    lease_token = NULL, lease_until = NULL,
                    last_failure_code = ?,
                    updated_at = GREATEST(updated_at, ?)
                WHERE status = 'PROCESSING' AND lease_until <= ?
                """, SettlementCategory.UNCATEGORIZED, SettlementCategorySource.UNCATEGORIZED.name(),
                now, now, SettlementCategoryFailureCode.PROCESSING_TIMEOUT.name(), now, now);
        return jdbc.query("""
                WITH due AS (
                    SELECT settlement_id FROM settlement_categories
                    WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ? AND attempt_count < max_attempts
                    ORDER BY next_attempt_at, settlement_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE settlement_categories AS job
                SET status = 'PROCESSING', attempt_count = job.attempt_count + 1, next_attempt_at = NULL,
                    lease_token = ?, lease_until = ?, updated_at = GREATEST(job.updated_at, ?)
                FROM due
                WHERE job.settlement_id = due.settlement_id
                RETURNING job.settlement_id, job.attempt_count, job.max_attempts, job.lease_token, job.lease_until
                """, this::map, now, UUID.randomUUID(), leaseUntil, now).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean classify(SettlementCategoryJob claim, String categoryCode, SettlementCategorySource source,
            LocalDateTime now) {
        if (discardIfSettlementDeleted(claim, now)) {
            return false;
        }
        return jdbc.update("""
                UPDATE settlement_categories SET
                    status = 'CLASSIFIED', category_code = ?, source = ?, classified_at = GREATEST(created_at, ?),
                    next_attempt_at = NULL, lease_token = NULL, lease_until = NULL, last_failure_code = NULL,
                    updated_at = GREATEST(updated_at, ?)
                WHERE """ + OWNED_CLAIM, categoryCode, source.name(), now, now,
                claim.settlementId(), claim.leaseToken(), now) == 1;
    }

    @Override
    @Transactional
    public boolean retry(SettlementCategoryJob claim, LocalDateTime nextAttemptAt,
            SettlementCategoryFailureCode failureCode, LocalDateTime now) {
        return jdbc.update("""
                UPDATE settlement_categories SET
                    status = 'RETRY', next_attempt_at = ?, lease_token = NULL, lease_until = NULL,
                    last_failure_code = ?, updated_at = GREATEST(updated_at, ?)
                WHERE """ + OWNED_CLAIM, nextAttemptAt, failureCode.name(), now,
                claim.settlementId(), claim.leaseToken(), now) == 1;
    }

    @Override
    @Transactional
    public boolean giveUp(SettlementCategoryJob claim, SettlementCategoryFailureCode failureCode, LocalDateTime now) {
        if (discardIfSettlementDeleted(claim, now)) {
            return false;
        }
        return jdbc.update("""
                UPDATE settlement_categories SET
                    status = 'CLASSIFIED', category_code = ?, source = ?, classified_at = GREATEST(created_at, ?),
                    next_attempt_at = NULL, lease_token = NULL, lease_until = NULL, last_failure_code = ?,
                    updated_at = GREATEST(updated_at, ?)
                WHERE """ + OWNED_CLAIM, SettlementCategory.UNCATEGORIZED,
                SettlementCategorySource.UNCATEGORIZED.name(), now, failureCode.name(), now,
                claim.settlementId(), claim.leaseToken(), now) == 1;
    }

    @Override
    @Transactional
    public boolean discard(SettlementCategoryJob claim, LocalDateTime now) {
        return jdbc.update("DELETE FROM settlement_categories WHERE " + OWNED_CLAIM,
                claim.settlementId(), claim.leaseToken(), now) == 1;
    }

    /**
     * 끝 상태로 저장하기 직전에 정산이 아직 살아 있는지 본다. 처리(특히 LLM 호출) 중에 정산이 삭제되면
     * 결과를 남기지 않고 작업을 지운다. 정산 행을 공유 잠금으로 읽어, 확인과 저장 사이에 삭제가 끼어들지 못하게 한다.
     */
    private boolean discardIfSettlementDeleted(SettlementCategoryJob claim, LocalDateTime now) {
        Boolean deleted = jdbc.query(
                "SELECT deleted_at IS NOT NULL AS deleted FROM settlements WHERE id = ? FOR SHARE",
                rows -> rows.next() ? rows.getBoolean("deleted") : null, claim.settlementId());
        if (Boolean.FALSE.equals(deleted)) {
            return false;
        }
        jdbc.update("DELETE FROM settlement_categories WHERE " + OWNED_CLAIM,
                claim.settlementId(), claim.leaseToken(), now);
        return true;
    }

    private SettlementCategoryJob map(ResultSet row, int index) throws SQLException {
        var leaseUntil = row.getTimestamp("lease_until");
        return new SettlementCategoryJob(
                row.getObject("settlement_id", UUID.class),
                row.getInt("attempt_count"),
                row.getInt("max_attempts"),
                row.getObject("lease_token", UUID.class),
                leaseUntil == null ? null : leaseUntil.toLocalDateTime());
    }
}
