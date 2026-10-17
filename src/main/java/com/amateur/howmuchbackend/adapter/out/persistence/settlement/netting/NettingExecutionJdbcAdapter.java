package com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingExecutionStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class NettingExecutionJdbcAdapter implements NettingExecutionStorePort {
    private final JdbcTemplate jdbc;
    private static final String SELECT =
            "SELECT e.*,COALESCE(e.target_netting_id,(SELECT n.id FROM group_settlement_nettings n"
                + " WHERE n.execution_id=e.id)) result_netting_id FROM group_netting_executions e ";

    public Execution enqueue(
            long group, UUID target, long actor, Kind kind, UUID key, LocalDateTime now) {
        var old = findByRequest(group, actor, kind, key);
        if (old.isPresent()) {
            if (kind != Kind.CREATE && !Objects.equals(old.get().nettingId(), target))
                throw ApplicationException.of(NettingStatusCode.NETTING_IDEMPOTENCY_CONFLICT);
            return old.get();
        }
        UUID id = UUID.randomUUID();
        List<UUID> inserted;
        try {
            inserted =
                    jdbc.query(
                            "INSERT INTO"
                                + " group_netting_executions(id,group_id,actor_id,kind,target_netting_id,status,idempotency_key,next_attempt_at,deadline,created_at,updated_at)"
                                + " VALUES(?,?,?,?,?,'QUEUED',?,?,?,?,?) ON CONFLICT ON CONSTRAINT"
                                + " uk_group_netting_execution_idempotency DO NOTHING RETURNING id",
                            (rs, row) -> rs.getObject(1, UUID.class),
                            id,
                            group,
                            actor,
                            kind.name(),
                            target,
                            key,
                            now,
                            now.plusMinutes(10),
                            now,
                            now);
        } catch (DuplicateKeyException conflict) {
            throw ApplicationException.of(NettingStatusCode.NETTING_ALREADY_ACTIVE);
        }
        if (inserted.isEmpty()) {
            var replay = findByRequest(group, actor, kind, key).orElseThrow();
            if (kind != Kind.CREATE && !Objects.equals(replay.nettingId(), target))
                throw ApplicationException.of(NettingStatusCode.NETTING_IDEMPOTENCY_CONFLICT);
            return replay;
        }
        return findById(id).orElseThrow();
    }

    public Optional<Execution> find(long group, UUID id) {
        return jdbc.query(SELECT + "WHERE e.group_id=? AND e.id=?", this::map, group, id).stream()
                .findFirst();
    }

    public Optional<Execution> findById(UUID id) {
        return jdbc.query(SELECT + "WHERE e.id=?", this::map, id).stream().findFirst();
    }

    public Optional<Execution> findByRequest(long group, long actor, Kind kind, UUID key) {
        return jdbc
                .query(
                        SELECT
                                + "WHERE e.group_id=? AND e.actor_id=? AND e.kind=? AND"
                                + " e.idempotency_key=?",
                        this::map,
                        group,
                        actor,
                        kind.name(),
                        key)
                .stream()
                .findFirst();
    }

    @Transactional
    public Optional<Execution> claimNext(UUID worker, LocalDateTime now) {
        jdbc.update(
                "WITH expired AS (SELECT id FROM group_netting_executions WHERE status IN"
                    + " ('QUEUED','SYNCING','RETRY') AND (deadline<=? OR (status='SYNCING' AND"
                    + " lease_until<=? AND attempt_count>=max_attempts)) ORDER BY created_at,id FOR"
                    + " UPDATE SKIP LOCKED LIMIT 100) UPDATE group_netting_executions e SET"
                    + " status='FAILED',error_code='NETTING_EXECUTION_EXPIRED',worker_id=NULL,lease_token=NULL,lease_until=NULL,next_attempt_at=NULL,finished_at=?,updated_at=?"
                    + " FROM expired x WHERE e.id=x.id",
                now,
                now,
                now,
                now);
        jdbc.update(
                "WITH abandoned AS (SELECT id FROM group_netting_executions WHERE status='SYNCING'"
                    + " AND lease_until<=? AND deadline>? AND attempt_count<max_attempts ORDER BY"
                    + " created_at,id FOR UPDATE SKIP LOCKED LIMIT 100) UPDATE"
                    + " group_netting_executions e SET"
                    + " status='RETRY',error_code='NETTING_LEASE_EXPIRED',worker_id=NULL,lease_token=NULL,lease_until=NULL,next_attempt_at=?,updated_at=?"
                    + " FROM abandoned a WHERE e.id=a.id",
                now,
                now,
                now,
                now);
        UUID token = UUID.randomUUID();
        var ids =
                jdbc.query(
                        """
WITH candidate AS (SELECT id FROM group_netting_executions
    WHERE deadline>? AND attempt_count<max_attempts
      AND status IN ('QUEUED','RETRY') AND next_attempt_at<=?
    ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
UPDATE group_netting_executions e SET status='SYNCING',attempt_count=attempt_count+1,
    worker_id=?,lease_token=?,lease_until=LEAST(?,deadline),next_attempt_at=NULL,updated_at=?
FROM candidate c WHERE e.id=c.id RETURNING e.id
""",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        now,
                        now,
                        worker,
                        token,
                        now.plusSeconds(90),
                        now);
        return ids.stream().findFirst().flatMap(this::findById);
    }

    public boolean heartbeat(UUID id, UUID token, UUID worker, LocalDateTime now) {
        return jdbc.update(
                        "UPDATE group_netting_executions SET"
                                + " lease_until=LEAST(?,deadline),updated_at=? WHERE id=? AND"
                                + " lease_token=? AND worker_id=? AND status='SYNCING' AND"
                                + " lease_until>? AND deadline>?",
                        now.plusSeconds(90),
                        now,
                        id,
                        token,
                        worker,
                        now,
                        now)
                == 1;
    }

    public void requireLease(UUID id, UUID token, LocalDateTime now) {
        if (jdbc.queryForList(
                        "SELECT id FROM group_netting_executions WHERE id=? AND lease_token=? AND"
                                + " status='SYNCING' AND lease_until>? AND deadline>? FOR UPDATE",
                        id,
                        token,
                        now,
                        now)
                .isEmpty())
            throw ApplicationException.of(NettingStatusCode.NETTING_EXECUTION_FENCED);
    }

    public void finish(
            UUID id, UUID token, Status status, UUID netting, String error, LocalDateTime now) {
        if (status == Status.RETRY) {
            jdbc.update(
                    "UPDATE group_netting_executions SET status=CASE WHEN"
                        + " attempt_count<max_attempts THEN 'RETRY' ELSE 'FAILED'"
                        + " END,error_code=?,next_attempt_at=CASE WHEN attempt_count<max_attempts"
                        + " THEN ? ELSE NULL END,finished_at=CASE WHEN attempt_count<max_attempts"
                        + " THEN NULL ELSE ?"
                        + " END,worker_id=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE"
                        + " id=? AND lease_token=? AND status='SYNCING' AND lease_until>? AND"
                        + " deadline>?",
                    error,
                    now.plusSeconds(5),
                    now,
                    now,
                    id,
                    token,
                    now,
                    now);
        } else
            jdbc.update(
                    "UPDATE group_netting_executions SET"
                        + " status=?,error_code=?,next_attempt_at=NULL,worker_id=NULL,lease_token=NULL,lease_until=NULL,finished_at=?,updated_at=?"
                        + " WHERE id=? AND lease_token=? AND status='SYNCING' AND lease_until>? AND"
                        + " deadline>?",
                    status.name(),
                    error,
                    now,
                    now,
                    id,
                    token,
                    now,
                    now);
    }

    private Execution map(ResultSet r, int row) throws SQLException {
        return new Execution(
                r.getObject("id", UUID.class),
                r.getLong("group_id"),
                r.getObject("result_netting_id", UUID.class),
                r.getLong("actor_id"),
                Kind.valueOf(r.getString("kind")),
                Status.valueOf(r.getString("status")),
                r.getObject("lease_token", UUID.class),
                r.getString("error_code"),
                r.getTimestamp("deadline").toLocalDateTime());
    }
}
