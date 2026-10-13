package com.amateur.howmuchbackend.adapter.out.persistence.recap;

import com.amateur.howmuchbackend.application.port.out.recap.DeleteOwnRecapsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class RecapOwnerDeletionPersistenceAdapter implements DeleteOwnRecapsPort {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteOwnedRecaps(Long ownerId, LocalDateTime now) {
        var params = new MapSqlParameterSource("owner", ownerId).addValue("now", Timestamp.valueOf(now));
        // Same owner -> job order as publication. Soft deletion never reaches FK cascades.
        jdbc.query("SELECT id FROM users WHERE id=:owner FOR UPDATE", params, (row, i) -> row.getLong("id"));
        jdbc.query("SELECT id FROM recap_generation_jobs WHERE owner_user_id=:owner ORDER BY id FOR UPDATE", params,
                (row, i) -> row.getObject("id", java.util.UUID.class));
        jdbc.update("""
                INSERT INTO recap_image_cleanup_tasks(id, file_name, status, next_attempt_at, created_at, updated_at)
                SELECT gen_random_uuid(), files.file_name, 'PENDING', :now, :now, :now FROM (
                    SELECT companion_avatar_file_name AS file_name FROM monthly_recaps WHERE owner_user_id=:owner
                    UNION
                    SELECT reserved_image_file_name AS file_name FROM recap_generation_jobs WHERE owner_user_id=:owner
                ) files WHERE files.file_name IS NOT NULL
                ON CONFLICT (file_name) DO NOTHING
                """, params);
        jdbc.update("DELETE FROM monthly_recaps WHERE owner_user_id=:owner", params);
        jdbc.update("DELETE FROM recap_generation_jobs WHERE owner_user_id=:owner", params);
    }
}
