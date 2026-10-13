package com.amateur.howmuchbackend.adapter.out.persistence.recap;

import com.amateur.howmuchbackend.application.port.out.recap.RecapImageCleanupStorePort;
import com.amateur.howmuchbackend.application.recap.model.RecapImageCleanupClaim;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RecapImageCleanupPersistenceAdapter implements RecapImageCleanupStorePort {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public void enqueue(String fileName, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO recap_image_cleanup_tasks(id, file_name, status, next_attempt_at, created_at, updated_at)
                VALUES (:id, :file, 'PENDING', :now, :now, :now) ON CONFLICT (file_name) DO NOTHING
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("file", fileName).addValue("now", Timestamp.valueOf(now)));
    }

    @Override
    public boolean isReferenced(String fileName, LocalDateTime now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM monthly_recaps WHERE companion_avatar_file_name = :file)
                    OR EXISTS (SELECT 1 FROM recap_generation_jobs
                        WHERE reserved_image_file_name = :file AND status = 'PROCESSING' AND lease_until > :now)
                """, new MapSqlParameterSource("file", fileName).addValue("now", Timestamp.valueOf(now)), Boolean.class));
    }

    @Override
    @Transactional
    public Optional<RecapImageCleanupClaim> claimNext(LocalDateTime now) {
        var params = new MapSqlParameterSource("now", Timestamp.valueOf(now))
                .addValue("until", Timestamp.valueOf(now.plusMinutes(10))).addValue("token", UUID.randomUUID());
        jdbc.update("""
                UPDATE recap_image_cleanup_tasks SET status = 'RETRY', next_attempt_at = :now,
                    processing_started_at = NULL, lease_token = NULL, lease_until = NULL,
                    last_failure_code = 'LEASE_EXPIRED', updated_at = :now
                WHERE status = 'PROCESSING' AND lease_until <= :now
                """, params);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM recap_image_cleanup_tasks WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE recap_image_cleanup_tasks task SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                    next_attempt_at = NULL, processing_started_at = :now, lease_token = :token, lease_until = :until, updated_at = :now
                FROM candidate WHERE task.id = candidate.id RETURNING task.id, task.file_name, task.lease_token
                """, params, (row, i) -> new RecapImageCleanupClaim(row.getObject("id", UUID.class), row.getString("file_name"),
                row.getObject("lease_token", UUID.class))).stream().findFirst();
    }

    @Override
    public void complete(RecapImageCleanupClaim claim) {
        jdbc.update("DELETE FROM recap_image_cleanup_tasks WHERE id = :id AND lease_token = :token AND status = 'PROCESSING'",
                params(claim));
    }

    @Override
    public void retry(RecapImageCleanupClaim claim, LocalDateTime now, String failureCode) {
        jdbc.update("""
                UPDATE recap_image_cleanup_tasks SET status = 'RETRY', next_attempt_at = :next,
                    processing_started_at = NULL, lease_token = NULL, lease_until = NULL,
                    last_failure_code = :failure, updated_at = :now
                WHERE id = :id AND lease_token = :token AND status = 'PROCESSING'
                """, params(claim).addValue("now", Timestamp.valueOf(now))
                .addValue("next", Timestamp.valueOf(now.plusHours(1))).addValue("failure", failureCode));
    }

    private MapSqlParameterSource params(RecapImageCleanupClaim claim) {
        return new MapSqlParameterSource("id", claim.id()).addValue("token", claim.token());
    }
}
