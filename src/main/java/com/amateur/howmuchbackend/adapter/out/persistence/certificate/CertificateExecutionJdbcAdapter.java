package com.amateur.howmuchbackend.adapter.out.persistence.certificate;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateResultCommand;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateExecutionStorePort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateExecutionJdbcAdapter
        implements CertificateExecutionStorePort,
                com.amateur.howmuchbackend.application.port.out.certificate
                        .CertificateResultInboxPort {
    private final JdbcTemplate jdbc;

    public CertificateExecutionJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean receive(ReceiveCertificateCommand c, String hash, LocalDateTime now) {
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO certificate_provider_command_inbox(message_id,session_id,session_version,command_type,organization_code,
                         payload_hash,upload_ref,deadline_at,status,next_attempt_at,received_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?,'PENDING',?,?,?) ON CONFLICT DO NOTHING
                        """,
                        c.messageId(),
                        c.sessionId(),
                        c.version(),
                        c.type(),
                        c.organization(),
                        hash,
                        c.uploadRef(),
                        c.deadline(),
                        now,
                        now,
                        now);
        if (inserted == 1) return true;
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM certificate_provider_command_inbox WHERE"
                                + " message_id=? AND payload_hash=?)",
                        Boolean.class,
                        c.messageId(),
                        hash));
    }

    public Optional<CertificateExecution> claim(LocalDateTime now) {
        return jdbc
                .query(
                        """
                        WITH candidate AS (
                         SELECT j.message_id FROM certificate_provider_command_inbox j
                         WHERE j.status IN ('PENDING','RETRY') AND j.next_attempt_at<=?
                          AND (j.command_type<>'CANCEL'
                           OR NOT EXISTS(SELECT 1 FROM certificate_provider_command_inbox r
                            WHERE r.session_id=j.session_id AND r.command_type='REGISTER'
                             AND r.status IN ('PROCESSING','UNKNOWN'))
                           OR EXISTS(SELECT 1 FROM certificate_provider_connections p
                            WHERE p.session_id=j.session_id AND p.status='READY'))
                         ORDER BY j.next_attempt_at,j.message_id FOR UPDATE SKIP LOCKED LIMIT 1)
                        UPDATE certificate_provider_command_inbox j SET status='PROCESSING',
                         attempt_count=attempt_count+1,lease_generation=lease_generation+1,
                         lease_token=?,processing_started_at=?,lease_expires_at=?,
                         next_attempt_at=NULL,updated_at=? FROM candidate c
                         WHERE j.message_id=c.message_id RETURNING j.*
                        """,
                        this::execution,
                        now,
                        UUID.randomUUID(),
                        now,
                        now.plusMinutes(10),
                        now)
                .stream()
                .findFirst();
    }

    private CertificateExecution execution(ResultSet r, int n) throws SQLException {
        return new CertificateExecution(
                new ReceiveCertificateCommand(
                        r.getObject("message_id", UUID.class),
                        r.getObject("session_id", UUID.class),
                        r.getLong("session_version"),
                        r.getString("command_type"),
                        r.getString("organization_code"),
                        r.getObject("upload_ref", UUID.class),
                        r.getObject("deadline_at", LocalDateTime.class),
                        r.getObject("received_at", LocalDateTime.class)),
                r.getObject("lease_token", UUID.class),
                r.getLong("lease_generation"));
    }

    public boolean startRequest(CertificateExecution e, LocalDateTime now) {
        var c = e.command();
        jdbc.queryForList(
                "SELECT id FROM certificate_registration_sessions WHERE id=? FOR UPDATE",
                c.sessionId());
        int changed =
                jdbc.update(
                        """
                        UPDATE certificate_provider_command_inbox SET provider_request_started_at=?,updated_at=?
                        WHERE message_id=? AND status='PROCESSING' AND lease_token=? AND lease_generation=?
                         AND lease_expires_at>? AND provider_request_started_at IS NULL
                        """,
                        now,
                        now,
                        c.messageId(),
                        e.leaseToken(),
                        e.generation(),
                        now);
        if (changed == 1)
            jdbc.update(
                    "UPDATE certificate_registration_sessions SET status='REGISTERING',updated_at=?"
                            + " WHERE id=?",
                    now,
                    c.sessionId());
        return changed == 1;
    }

    public void retry(CertificateExecution e, LocalDateTime now) {
        if (e.command().deadline() != null && !e.command().deadline().isAfter(now)) {
            finish(e, "FAILED", "UPLOAD_EXPIRED", null, null, now);
            return;
        }
        jdbc.update(
                """
                UPDATE certificate_provider_command_inbox SET status='RETRY',next_attempt_at=?,last_failure_code='PROVIDER_UNAVAILABLE',
                 lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,updated_at=?
                WHERE message_id=? AND status='PROCESSING' AND lease_token=? AND lease_generation=? AND lease_expires_at>?
                 AND provider_request_started_at IS NULL
                """,
                now.plusSeconds(10),
                now,
                e.command().messageId(),
                e.leaseToken(),
                e.generation(),
                now);
    }

    public void finish(
            CertificateExecution e,
            String status,
            String code,
            UUID connectionRef,
            CertificateEnvelope secret,
            LocalDateTime now) {
        if (!Set.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(status))
            throw new IllegalArgumentException("Invalid execution outcome");
        var c = e.command();
        // Parent-session first is also the lock order used by cancellation and the connection
        // trigger.
        jdbc.queryForList(
                "SELECT id FROM certificate_registration_sessions WHERE id=? FOR UPDATE",
                c.sessionId());
        var rows =
                jdbc.queryForList(
                        "SELECT"
                            + " status,lease_token,lease_generation,provider_request_started_at,lease_expires_at"
                            + " FROM certificate_provider_command_inbox WHERE message_id=? FOR"
                            + " UPDATE",
                        c.messageId());
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        boolean current =
                ((Number) row.get("lease_generation")).longValue() == e.generation()
                        && (("PROCESSING".equals(row.get("status"))
                                        && e.leaseToken().equals(row.get("lease_token")))
                                || ("UNKNOWN".equals(row.get("status"))
                                        && row.get("provider_request_started_at") != null));
        if ("PROCESSING".equals(row.get("status"))
                && row.get("provider_request_started_at") == null
                && !((Timestamp) row.get("lease_expires_at")).toLocalDateTime().isAfter(now))
            return;
        if (!current || ("UNKNOWN".equals(row.get("status")) && status.equals("UNKNOWN"))) return;
        if (connectionRef != null) {
            jdbc.update(
                    """
                    INSERT INTO certificate_provider_connections(connection_ref,session_id,session_version,organization_code,
                     registration_message_id,status,encrypted_payload,wrapped_data_key,encryption_key_id,created_at,updated_at)
                    VALUES(?,?,?,?,?,'READY',?,?,?,?,?)
                    """,
                    connectionRef,
                    c.sessionId(),
                    c.version(),
                    c.organization(),
                    c.messageId(),
                    secret.payload(),
                    secret.wrappedKey(),
                    secret.keyId(),
                    now,
                    now);
        }
        jdbc.update(
                """
                UPDATE certificate_provider_command_inbox SET status=?,last_failure_code=?,completed_at=?,updated_at=?,
                 lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,next_attempt_at=NULL WHERE message_id=?
                """,
                status,
                code,
                now,
                now,
                c.messageId());
        if (c.type().equals("REGISTER")) {
            if (status.equals("UNKNOWN")) {
                // Preserve uncertainty before result delivery; recovery must not offer a retry
                // while the provider outcome is unknown, even during a result-topic outage.
                jdbc.update(
                        """
                        UPDATE certificate_registration_sessions SET status='UNKNOWN',last_failure_code=?,updated_at=?
                        WHERE id=? AND registration_version=? AND status IN ('SUBMITTED','REGISTERING','UNKNOWN')
                        """,
                        code,
                        now,
                        c.sessionId(),
                        c.version());
            }
            String type =
                    status.equals("SUCCEEDED")
                            ? "REGISTERED"
                            : status.equals("UNKNOWN")
                                    ? "REGISTRATION_UNKNOWN"
                                    : "REGISTRATION_FAILED";
            emit(c, type, connectionRef, code, now);
            jdbc.update(
                    """
                    UPDATE certificate_upload_secrets SET status='CONSUMED',encrypted_payload=NULL,wrapped_data_key=NULL,
                     encryption_key_id=NULL,cleared_at=?,updated_at=? WHERE upload_ref=? AND status='STORED'
                    """,
                    now,
                    now,
                    c.uploadRef());
        }
    }

    private void emit(
            ReceiveCertificateCommand c, String type, UUID ref, String code, LocalDateTime now) {
        jdbc.update(
                """
                INSERT INTO certificate_provider_result_outbox(message_id,command_message_id,session_id,session_version,
                 message_type,connection_ref,event_failure_code,status,next_attempt_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'PENDING',?,?,?) ON CONFLICT(command_message_id,message_type) DO NOTHING
                """,
                UUID.randomUUID(),
                c.messageId(),
                c.sessionId(),
                c.version(),
                type,
                ref,
                code,
                now,
                now,
                now);
    }

    public CertificateCleanupTarget cleanupTarget(CertificateExecution e, LocalDateTime now) {
        var c = e.command();
        var sessions =
                jdbc.queryForList(
                        "SELECT status,approval_id FROM certificate_registration_sessions WHERE"
                            + " id=? AND registration_version=? AND organization_code=? FOR UPDATE",
                        c.sessionId(),
                        c.version(),
                        c.organization());
        if (sessions.isEmpty()
                || sessions.getFirst().get("approval_id") != null
                || !Set.of("CANCELLED", "EXPIRED", "FAILED")
                        .contains(sessions.getFirst().get("status")))
            return CertificateCleanupTarget.none();
        var connections =
                jdbc.query(
                        "SELECT connection_ref,encrypted_payload,wrapped_data_key,encryption_key_id"
                                + " FROM certificate_provider_connections WHERE session_id=? AND"
                                + " status='READY'",
                        (r, n) ->
                                new CertificateCleanupTarget(
                                        "READY",
                                        r.getObject(1, UUID.class),
                                        new CertificateEnvelope(
                                                r.getBytes(2), r.getBytes(3), r.getString(4))),
                        c.sessionId());
        if (!connections.isEmpty()) return connections.getFirst();
        boolean pending =
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM certificate_provider_command_inbox"
                                    + " WHERE session_id=? AND command_type='REGISTER' AND status"
                                    + " IN ('PROCESSING','UNKNOWN'))",
                                Boolean.class,
                                c.sessionId()));
        return pending ? CertificateCleanupTarget.waiting() : CertificateCleanupTarget.none();
    }

    public boolean startCleanup(CertificateExecution e, UUID ref, LocalDateTime now) {
        var target = cleanupTarget(e, now);
        if (!target.state().equals("READY") || !ref.equals(target.connectionRef())) return false;
        return jdbc.update(
                        "UPDATE certificate_provider_command_inbox SET"
                            + " provider_request_started_at=?,updated_at=? WHERE message_id=? AND"
                            + " command_type='CANCEL' AND status='PROCESSING' AND lease_token=? AND"
                            + " lease_generation=? AND lease_expires_at>? AND"
                            + " provider_request_started_at IS NULL",
                        now,
                        now,
                        e.command().messageId(),
                        e.leaseToken(),
                        e.generation(),
                        now)
                == 1;
    }

    public void finishCleanup(CertificateExecution e, boolean deleted, LocalDateTime now) {
        jdbc.queryForList(
                "SELECT id FROM certificate_registration_sessions WHERE id=? FOR UPDATE",
                e.command().sessionId());
        var rows =
                jdbc.queryForList(
                        "SELECT status,lease_token,lease_generation,provider_request_started_at"
                                + " FROM certificate_provider_command_inbox WHERE message_id=? AND"
                                + " command_type='CANCEL' FOR UPDATE",
                        e.command().messageId());
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        if (((Number) row.get("lease_generation")).longValue() != e.generation()
                || row.get("provider_request_started_at") == null
                || !("PROCESSING".equals(row.get("status"))
                                && e.leaseToken().equals(row.get("lease_token"))
                        || "UNKNOWN".equals(row.get("status")))) return;
        jdbc.update(
                "UPDATE certificate_provider_connections SET status=?,encrypted_payload=CASE WHEN ?"
                    + " THEN NULL ELSE encrypted_payload END,wrapped_data_key=CASE WHEN ? THEN NULL"
                    + " ELSE wrapped_data_key END,encryption_key_id=CASE WHEN ? THEN NULL ELSE"
                    + " encryption_key_id END,cleared_at=?,updated_at=? WHERE session_id=? AND"
                    + " status IN ('READY','DELETE_UNKNOWN') AND approval_id IS NULL",
                deleted ? "DISCARDED" : "DELETE_UNKNOWN",
                deleted,
                deleted,
                deleted,
                deleted ? now : null,
                now,
                e.command().sessionId());
        finish(
                e,
                deleted ? "SUCCEEDED" : "UNKNOWN",
                deleted ? null : "PROVIDER_UNAVAILABLE",
                null,
                null,
                now);
    }

    public void recover(LocalDateTime now) {
        // Lock sessions before jobs to avoid deadlocks with completion/cancellation. A small
        // bounded batch.
        var ids =
                jdbc.query(
                        """
                        SELECT s.id FROM certificate_registration_sessions s WHERE EXISTS(SELECT 1 FROM certificate_provider_command_inbox j
                         WHERE j.session_id=s.id AND j.status='PROCESSING' AND j.lease_expires_at<=?)
                        ORDER BY s.id FOR UPDATE SKIP LOCKED LIMIT 20
                        """,
                        (r, n) -> r.getObject(1, UUID.class),
                        now);
        for (UUID id : ids) {
            var expired =
                    jdbc.query(
                            "SELECT * FROM certificate_provider_command_inbox WHERE session_id=?"
                                    + " AND status='PROCESSING' AND lease_expires_at<=? FOR UPDATE",
                            this::execution,
                            id,
                            now);
            for (var e : expired) {
                boolean started =
                        Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "SELECT provider_request_started_at IS NOT NULL FROM"
                                                + " certificate_provider_command_inbox WHERE"
                                                + " message_id=?",
                                        Boolean.class,
                                        e.command().messageId()));
                if (started) {
                    if (e.command().type().equals("CANCEL")) finishCleanup(e, false, now);
                    else finish(e, "UNKNOWN", "REGISTRATION_UNCERTAIN", null, null, now);
                } else
                    jdbc.update(
                            """
                            UPDATE certificate_provider_command_inbox SET status='RETRY',last_failure_code='LEASE_EXPIRED',next_attempt_at=?,
                             lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,updated_at=? WHERE message_id=?
                            """,
                            now,
                            now,
                            e.command().messageId());
            }
        }
        var sessions =
                jdbc.query(
                        "SELECT id FROM certificate_registration_sessions WHERE expires_at<=? AND"
                            + " approval_id IS NULL AND status NOT IN"
                            + " ('EXPIRED','CANCELLED','FAILED') AND NOT (status='UNKNOWN' AND"
                            + " (EXISTS(SELECT 1 FROM certificate_registration_consents c WHERE"
                            + " c.session_id=certificate_registration_sessions.id) OR EXISTS(SELECT"
                            + " 1 FROM certificate_pc_pairings p WHERE"
                            + " p.registration_session_id=certificate_registration_sessions.id)))"
                            + " ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 20",
                        (r, n) -> r.getObject(1, UUID.class),
                        now);
        for (UUID id : sessions) {
            jdbc.update(
                    "UPDATE certificate_registration_claims SET status='EXPIRED',finished_at=?"
                            + " WHERE session_id=? AND status='RESERVED'",
                    now,
                    id);
            jdbc.update(
                    "INSERT INTO"
                        + " certificate_command_outbox(message_id,session_id,session_version,command_type,status,next_attempt_at,created_at,updated_at)"
                        + " SELECT ?,id,registration_version,'CANCEL','PENDING',?,?,? FROM"
                        + " certificate_registration_sessions WHERE id=? ON"
                        + " CONFLICT(session_id,command_type,session_version) DO NOTHING",
                    UUID.randomUUID(),
                    now,
                    now,
                    now,
                    id);
            jdbc.update(
                    "UPDATE certificate_registration_sessions SET status='EXPIRED'"
                        + " ,qr_token_hash=NULL,qr_expires_at=NULL,current_claim_id=NULL,updated_at=?"
                        + " WHERE id=?",
                    now,
                    id);
        }
        jdbc.update(
                """
                UPDATE certificate_upload_secrets SET status='EXPIRED' ,encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,
                 cleared_at=?,updated_at=? WHERE status IN ('ISSUED','STORED') AND expires_at<=?
                """,
                now,
                now,
                now);
    }

    public boolean apply(
            ReceiveCertificateResultCommand e, String hash, boolean mayApply, LocalDateTime now) {
        // Inbox receipt and effect share this transaction. Reordered UNKNOWN cannot replace a
        // confirmed READY result.
        var prior =
                jdbc.queryForList(
                        "SELECT payload_hash FROM certificate_result_inbox WHERE message_id=?",
                        e.messageId());
        if (!prior.isEmpty()) return hash.equals(prior.getFirst().get("payload_hash"));
        jdbc.queryForList(
                "SELECT id FROM certificate_registration_sessions WHERE id=? FOR UPDATE",
                e.sessionId());
        prior =
                jdbc.queryForList(
                        "SELECT payload_hash FROM certificate_result_inbox WHERE message_id=?",
                        e.messageId());
        if (!prior.isEmpty()) return hash.equals(prior.getFirst().get("payload_hash"));
        int changed = 0;
        if (mayApply && e.type().equals("REGISTERED")) {
            // READY is represented by an expired opaque QR until the PC explicitly mints the
            // one-time token.
            changed =
                    jdbc.update(
                            """
                            UPDATE certificate_registration_sessions s SET status='READY_FOR_CLAIM',connection_ref=?,claim_generation=claim_generation+1,
                             qr_token_hash=?,qr_expires_at=least(expires_at,created_at + interval '1 microsecond'),last_failure_code=NULL,updated_at=?
                            WHERE id=? AND registration_version=? AND status IN ('SUBMITTED','REGISTERING','UNKNOWN') AND expires_at>?
                             AND EXISTS(SELECT 1 FROM certificate_provider_connections p WHERE p.connection_ref=? AND p.session_id=s.id AND p.session_version=s.registration_version)
                            """,
                            e.connectionRef(),
                            UUID.randomUUID().toString().replace("-", "").repeat(2),
                            now,
                            e.sessionId(),
                            e.version(),
                            now,
                            e.connectionRef());
        } else if (mayApply
                && Set.of("REGISTRATION_FAILED", "REGISTRATION_UNKNOWN").contains(e.type())) {
            changed =
                    jdbc.update(
                            """
                            UPDATE certificate_registration_sessions SET status=?,last_failure_code=?,updated_at=?
                            WHERE id=? AND registration_version=? AND status IN ('SUBMITTED','REGISTERING','UNKNOWN') AND expires_at>?
                            """,
                            e.type().equals("REGISTRATION_UNKNOWN") ? "UNKNOWN" : "FAILED",
                            e.failureCode(),
                            now,
                            e.sessionId(),
                            e.version(),
                            now);
        }
        jdbc.update(
                """
                INSERT INTO certificate_result_inbox(message_id,session_id,session_version,message_type,payload_hash,disposition,received_at,processed_at)
                VALUES(?,?,?,?,?,?,?,?)
                """,
                e.messageId(),
                e.sessionId(),
                e.version(),
                e.type(),
                hash,
                changed == 1 ? "APPLIED" : "IGNORED",
                now,
                now);
        return true;
    }

    public Optional<CertificateEnvelope> readyConnection(UUID session, UUID ref) {
        return jdbc
                .query(
                        "SELECT encrypted_payload,wrapped_data_key,encryption_key_id FROM"
                                + " certificate_provider_connections WHERE session_id=? AND"
                                + " connection_ref=? AND status='READY'",
                        (r, n) ->
                                new CertificateEnvelope(
                                        r.getBytes(1), r.getBytes(2), r.getString(3)),
                        session,
                        ref)
                .stream()
                .findFirst();
    }

    public void discardConnection(UUID session, LocalDateTime now) {
        jdbc.update(
                "UPDATE certificate_provider_connections SET"
                    + " status='DISCARDED',encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,cleared_at=?,updated_at=?"
                    + " WHERE session_id=? AND status='READY'",
                now,
                now,
                session);
    }
}
