package com.amateur.howmuchbackend.adapter.out.persistence.recap;

import com.amateur.howmuchbackend.application.port.out.recap.RecapGenerationJobStorePort;
import com.amateur.howmuchbackend.application.recap.model.RecapGenerationClaim;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RecapGenerationJobPersistenceAdapter implements RecapGenerationJobStorePort {
    private static final String OWNED = "id = :id AND status = 'PROCESSING' AND lease_token = :token AND lease_until > :now";
    private static final String RELEASE = "processing_started_at = NULL, lease_token = NULL, lease_until = NULL, reserved_image_file_name = NULL";
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    @Transactional
    public void registerMissingMonths(YearMonth lastMonth, LocalDateTime now, LocalDateTime latestAvailableAt) {
        var params = new MapSqlParameterSource("month", Date.valueOf(lastMonth.atDay(1)))
                .addValue("now", Timestamp.valueOf(now)).addValue("available", Timestamp.valueOf(latestAvailableAt));
        jdbc.update("""
                INSERT INTO recap_generation_state(id, first_month, created_at, updated_at)
                VALUES (1, :month, :now, :now) ON CONFLICT (id) DO NOTHING
                """, params);
        var first = jdbc.queryForObject("SELECT first_month FROM recap_generation_state WHERE id = 1 FOR UPDATE", params, Date.class);
        params.addValue("first", first);
        jdbc.update("""
                INSERT INTO recap_generation_jobs(id, owner_user_id, month_start, status, next_attempt_at, created_at, updated_at)
                SELECT gen_random_uuid(), u.id, month::DATE, 'PENDING',
                    CASE WHEN month::DATE = CAST(:month AS DATE) THEN GREATEST(CAST(:now AS TIMESTAMP), CAST(:available AS TIMESTAMP)) ELSE CAST(:now AS TIMESTAMP) END, :now, :now
                FROM users u CROSS JOIN generate_series(CAST(:first AS DATE), CAST(:month AS DATE), INTERVAL '1 month') month
                WHERE u.active = TRUE
                ON CONFLICT (owner_user_id, month_start) DO NOTHING
                """, params);
        jdbc.update("""
                UPDATE recap_generation_state
                SET registered_through_month = GREATEST(registered_through_month, CAST(:month AS DATE)), updated_at = :now
                WHERE id = 1
                """, params);
    }

    @Override
    @Transactional
    public int recoverExpired(LocalDateTime now) {
        var params = new MapSqlParameterSource("now", Timestamp.valueOf(now));
        var ids = jdbc.query("""
                SELECT id FROM recap_generation_jobs WHERE status = 'PROCESSING' AND lease_until <= :now
                ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
                """, params, (row, i) -> row.getObject("id", UUID.class));
        for (UUID id : ids) {
            params.addValue("id", id);
            enqueueReserved(params, "id = :id");
            jdbc.update("UPDATE recap_generation_jobs SET status = 'RETRY', next_attempt_at = :now, " + RELEASE
                    + ", last_failure_code = 'LEASE_EXPIRED', updated_at = :now WHERE id = :id", params);
        }
        return ids.size();
    }

    @Override
    @Transactional
    public Optional<RecapGenerationClaim> claimNext(LocalDateTime now) {
        var params = new MapSqlParameterSource("now", Timestamp.valueOf(now))
                .addValue("until", Timestamp.valueOf(now.plusMinutes(10))).addValue("token", UUID.randomUUID());
        return jdbc.query("""
                WITH candidate AS (
                    SELECT job.id FROM recap_generation_jobs job
                    WHERE job.status IN ('PENDING', 'RETRY') AND job.next_attempt_at <= :now
                      AND NOT EXISTS (
                          SELECT 1 FROM recap_generation_jobs earlier
                          WHERE earlier.owner_user_id = job.owner_user_id AND earlier.month_start < job.month_start
                            AND earlier.status IN ('PENDING', 'RETRY', 'PROCESSING')
                      )
                    ORDER BY job.month_start, job.owner_user_id, job.id
                    LIMIT 1 FOR UPDATE OF job SKIP LOCKED
                )
                UPDATE recap_generation_jobs job SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                    next_attempt_at = NULL, processing_started_at = :now, lease_token = :token, lease_until = :until, updated_at = :now
                FROM candidate WHERE job.id = candidate.id
                RETURNING job.id, job.owner_user_id, job.month_start, job.lease_token, job.attempt_count
                """, params, (row, i) -> new RecapGenerationClaim(row.getObject("id", UUID.class), row.getLong("owner_user_id"),
                YearMonth.from(row.getDate("month_start").toLocalDate()), row.getObject("lease_token", UUID.class),
                row.getInt("attempt_count"))).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean renew(RecapGenerationClaim claim, LocalDateTime now) {
        return jdbc.update("UPDATE recap_generation_jobs SET lease_until = :until, updated_at = :now WHERE " + OWNED,
                params(claim, now).addValue("until", Timestamp.valueOf(now.plusMinutes(10)))) == 1;
    }

    @Override
    @Transactional
    public boolean reserveImage(RecapGenerationClaim claim, String fileName, LocalDateTime now) {
        return jdbc.update("UPDATE recap_generation_jobs SET reserved_image_file_name = :file, updated_at = :now WHERE "
                + OWNED + " AND reserved_image_file_name IS NULL", params(claim, now).addValue("file", fileName)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockOwned(RecapGenerationClaim claim, LocalDateTime now) {
        return !jdbc.query("SELECT id FROM recap_generation_jobs WHERE " + OWNED + " FOR UPDATE",
                params(claim, now), (row, i) -> row.getObject("id", UUID.class)).isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(RecapGenerationClaim claim, LocalDateTime now, boolean imageStored) {
        var params = params(claim, now);
        if (!imageStored) enqueueReserved(params, OWNED);
        return jdbc.update("UPDATE recap_generation_jobs SET status = 'COMPLETED', next_attempt_at = NULL, " + RELEASE
                + ", completed_at = :now, last_failure_code = NULL, skip_reason = NULL, updated_at = :now WHERE " + OWNED, params) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean skip(RecapGenerationClaim claim, LocalDateTime now, boolean ownerActive) {
        var params = params(claim, now).addValue("reason", ownerActive ? "NO_COMPLETED_SETTLEMENTS" : "OWNER_INACTIVE");
        enqueueReserved(params, OWNED);
        return jdbc.update("UPDATE recap_generation_jobs SET status = 'SKIPPED', next_attempt_at = NULL, " + RELEASE
                + ", completed_at = :now, last_failure_code = NULL, skip_reason = :reason, updated_at = :now WHERE " + OWNED, params) == 1;
    }

    @Override
    @Transactional
    public void retry(RecapGenerationClaim claim, LocalDateTime now, String failureCode) {
        if (!lockOwned(claim, now)) return;
        var params = params(claim, now).addValue("failure", failureCode)
                .addValue("next", Timestamp.valueOf(now.plusMinutes(Math.min(60, claim.attemptCount() * 5L))));
        enqueueReserved(params, OWNED);
        jdbc.update("UPDATE recap_generation_jobs SET status = 'RETRY', next_attempt_at = :next, " + RELEASE
                + ", last_failure_code = :failure, updated_at = :now WHERE " + OWNED, params);
    }

    private void enqueueReserved(MapSqlParameterSource params, String condition) {
        jdbc.update("""
                INSERT INTO recap_image_cleanup_tasks(id, file_name, status, next_attempt_at, created_at, updated_at)
                SELECT gen_random_uuid(), reserved_image_file_name, 'PENDING', :now, :now, :now
                FROM recap_generation_jobs WHERE reserved_image_file_name IS NOT NULL AND
                """ + condition + " ON CONFLICT (file_name) DO NOTHING", params);
    }

    private MapSqlParameterSource params(RecapGenerationClaim claim, LocalDateTime now) {
        return new MapSqlParameterSource("id", claim.id()).addValue("token", claim.token()).addValue("now", Timestamp.valueOf(now));
    }
}
