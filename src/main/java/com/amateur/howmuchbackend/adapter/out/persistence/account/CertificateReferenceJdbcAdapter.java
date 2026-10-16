package com.amateur.howmuchbackend.adapter.out.persistence.account;

import com.amateur.howmuchbackend.application.account.model.*;
import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;
import com.amateur.howmuchbackend.application.port.out.account.CertificateReferenceOperationStorePort;
import com.amateur.howmuchbackend.domain.account.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Component
@ConditionalOnProperty(
        prefix = "howmuch.certificate-reference",
        name = "enabled",
        havingValue = "true")
public class CertificateReferenceJdbcAdapter implements CertificateReferenceOperationStorePort {
    private final JdbcTemplate jdbc;

    public CertificateReferenceJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CertificateReferenceOperation> find(UUID id, long user) {
        return jdbc
                .query(
                        "SELECT * FROM certificate_reference_operations WHERE id=? AND user_id=?",
                        this::operation,
                        id,
                        user)
                .stream()
                .findFirst();
    }

    public Optional<CertificateReferenceOperation> findByKey(long user, UUID key) {
        return jdbc
                .query(
                        "SELECT * FROM certificate_reference_operations WHERE user_id=? AND"
                                + " idempotency_key=?",
                        this::operation,
                        user,
                        key)
                .stream()
                .findFirst();
    }

    public Optional<CertificateReferenceOperation> findActive(long user) {
        return jdbc
                .query(
                        """
                        SELECT * FROM certificate_reference_operations WHERE user_id=?
                        AND status IN ('QUEUED','RUNNING','UNKNOWN','ACTION_REQUIRED')
                        """,
                        this::operation,
                        user)
                .stream()
                .findFirst();
    }

    public void create(CertificateReferenceOperation op, CertificateEnvelope secret) {
        jdbc.update(
                """
INSERT INTO certificate_reference_operations(id,user_id,external_connection_id,source_organization,
 organization,idempotency_key,request_hash,connection_hash,status,encrypted_payload,wrapped_data_key,
 encryption_key_id,next_attempt_at,created_at,expires_at,updated_at)
VALUES(?,?,?,?,?,?,?,?,'QUEUED',?,?,?,?,?,?,?)
""",
                op.id(),
                op.userId(),
                op.connectionId(),
                op.sourceOrganization(),
                op.organization(),
                op.idempotencyKey(),
                op.requestHash(),
                op.connectionHash(),
                secret.payload(),
                secret.wrappedKey(),
                secret.keyId(),
                op.createdAt(),
                op.createdAt(),
                op.expiresAt(),
                op.createdAt());
        jdbc.update(
                """
INSERT INTO certificate_reference_command_outbox(message_id,operation_id,next_attempt_at,created_at)
VALUES(?,?,?,?)
""",
                op.id(),
                op.id(),
                op.createdAt(),
                op.createdAt());
    }

    public boolean receive(UUID message, UUID operation, String hash, LocalDateTime now) {
        Boolean expected =
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM certificate_reference_command_outbox WHERE message_id=? AND operation_id=?)
""",
                        Boolean.class,
                        message,
                        operation);
        if (!Boolean.TRUE.equals(expected)) return false;
        jdbc.update(
                """
INSERT INTO certificate_reference_command_inbox(message_id,operation_id,payload_hash,received_at)
VALUES(?,?,?,?) ON CONFLICT DO NOTHING
""",
                message,
                operation,
                hash,
                now);
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM certificate_reference_command_inbox
                         WHERE message_id=? AND operation_id=? AND payload_hash=?)
                        """,
                        Boolean.class,
                        message,
                        operation,
                        hash));
    }

    public Optional<CertificateReferenceExecution> claim(LocalDateTime now) {
        return jdbc
                .query(
                        """
WITH candidate AS (
 SELECT o.id FROM certificate_reference_operations o WHERE
  o.status IN ('QUEUED','UNKNOWN') AND o.next_attempt_at<=?
  AND (o.lease_token IS NULL OR o.lease_expires_at<=?)
  AND EXISTS(SELECT 1 FROM certificate_reference_command_inbox i WHERE i.operation_id=o.id)
 ORDER BY o.next_attempt_at,o.id FOR UPDATE SKIP LOCKED LIMIT 1)
UPDATE certificate_reference_operations o SET
 status=CASE WHEN status='UNKNOWN' THEN 'UNKNOWN' ELSE 'RUNNING' END,
 lease_generation=lease_generation+1,lease_token=?,lease_expires_at=?,
 processing_started_at=coalesce(processing_started_at,?),updated_at=?
FROM candidate c WHERE o.id=c.id RETURNING o.*
""",
                        (r, n) -> {
                            byte[] payload = r.getBytes("encrypted_payload");
                            return new CertificateReferenceExecution(
                                    operation(r, n),
                                    r.getObject("lease_token", UUID.class),
                                    r.getLong("lease_generation"),
                                    payload == null
                                            ? null
                                            : new CertificateEnvelope(
                                                    payload,
                                                    r.getBytes("wrapped_data_key"),
                                                    r.getString("encryption_key_id")));
                        },
                        now,
                        now,
                        UUID.randomUUID(),
                        now.plusMinutes(15),
                        now,
                        now)
                .stream()
                .findFirst();
    }

    public boolean markProviderStarted(CertificateReferenceExecution e, LocalDateTime now) {
        return jdbc.update(
                        """
UPDATE certificate_reference_operations SET provider_request_started_at=?,updated_at=?
WHERE id=? AND status='RUNNING' AND provider_request_started_at IS NULL
 AND lease_token=? AND lease_generation=? AND lease_expires_at>? AND expires_at>?
""",
                        now,
                        now,
                        e.operation().id(),
                        e.leaseToken(),
                        e.generation(),
                        now,
                        now.plusMinutes(6))
                == 1;
    }

    public boolean finish(
            CertificateReferenceExecution e,
            CertificateReferenceStatus status,
            String code,
            boolean existing,
            LocalDateTime now) {
        if (status == CertificateReferenceStatus.QUEUED
                || status == CertificateReferenceStatus.RUNNING)
            throw new IllegalArgumentException("Not a reference execution result");
        boolean terminal = status.terminal();
        return jdbc.update(
                        """
UPDATE certificate_reference_operations SET status=?,failure_code=?,already_connected=?,
 completed_at=?,lease_token=NULL,lease_expires_at=NULL,next_attempt_at=?,updated_at=?,
 encrypted_payload=CASE WHEN ? THEN NULL ELSE encrypted_payload END,
 wrapped_data_key=CASE WHEN ? THEN NULL ELSE wrapped_data_key END,
 encryption_key_id=CASE WHEN ? THEN NULL ELSE encryption_key_id END
WHERE id=? AND status IN ('RUNNING','UNKNOWN') AND lease_token=? AND lease_generation=?
 AND lease_expires_at>?
""",
                        status.name(),
                        code,
                        existing,
                        terminal ? now : null,
                        now.plusSeconds(30),
                        now,
                        terminal,
                        terminal,
                        terminal,
                        e.operation().id(),
                        e.leaseToken(),
                        e.generation(),
                        now)
                == 1;
    }

    public void defer(CertificateReferenceExecution e, LocalDateTime now) {
        jdbc.update(
                """
UPDATE certificate_reference_operations SET
 status=CASE WHEN provider_request_started_at IS NULL THEN 'QUEUED' ELSE 'UNKNOWN' END,
 failure_code=CASE WHEN provider_request_started_at IS NULL THEN NULL ELSE 'CERTIFICATE_REFERENCE_UNCERTAIN' END,
 lease_token=NULL,lease_expires_at=NULL,next_attempt_at=?,updated_at=?
WHERE id=? AND status IN ('RUNNING','UNKNOWN') AND lease_token=? AND lease_generation=? AND lease_expires_at>?
""",
                now.plusSeconds(30),
                now,
                e.operation().id(),
                e.leaseToken(),
                e.generation(),
                now);
    }

    public Optional<CertificateReferencePublication> claimPublication(LocalDateTime now) {
        return jdbc
                .query(
                        """
WITH candidate AS (SELECT message_id FROM certificate_reference_command_outbox
 WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,message_id
 FOR UPDATE SKIP LOCKED LIMIT 1)
UPDATE certificate_reference_command_outbox o SET status='PROCESSING',
 lease_token=?,lease_generation=lease_generation+1,lease_expires_at=?
FROM candidate c WHERE o.message_id=c.message_id RETURNING o.*
""",
                        (r, n) ->
                                new CertificateReferencePublication(
                                        r.getObject("message_id", UUID.class),
                                        r.getObject("operation_id", UUID.class),
                                        r.getObject("created_at", LocalDateTime.class),
                                        r.getObject("lease_token", UUID.class),
                                        r.getLong("lease_generation")),
                        now,
                        UUID.randomUUID(),
                        now.plusSeconds(30))
                .stream()
                .findFirst();
    }

    public void published(CertificateReferencePublication p, LocalDateTime now) {
        jdbc.update(
                """
UPDATE certificate_reference_command_outbox SET status='PUBLISHED',published_at=?,
 lease_token=NULL,lease_expires_at=NULL
WHERE message_id=? AND status='PROCESSING' AND lease_token=? AND lease_generation=? AND lease_expires_at>?
""",
                now,
                p.messageId(),
                p.leaseToken(),
                p.generation(),
                now);
    }

    public void publicationFailed(CertificateReferencePublication p, LocalDateTime now) {
        jdbc.update(
                """
UPDATE certificate_reference_command_outbox SET status='PENDING',next_attempt_at=?,
 lease_token=NULL,lease_expires_at=NULL
WHERE message_id=? AND status='PROCESSING' AND lease_token=? AND lease_generation=? AND lease_expires_at>?
""",
                now.plusSeconds(5),
                p.messageId(),
                p.leaseToken(),
                p.generation(),
                now);
    }

    public void recover(LocalDateTime now) {
        jdbc.update(
                """
                UPDATE certificate_reference_operations SET lease_token=NULL,lease_expires_at=NULL,
                 next_attempt_at=?,updated_at=? WHERE status='UNKNOWN' AND lease_expires_at<=?
                """,
                now,
                now,
                now);
        jdbc.update(
                """
UPDATE certificate_reference_operations SET
 status=CASE WHEN provider_request_started_at IS NULL THEN 'QUEUED' ELSE 'UNKNOWN' END,
 failure_code=CASE WHEN provider_request_started_at IS NULL THEN NULL ELSE 'CERTIFICATE_REFERENCE_UNCERTAIN' END,
 lease_token=NULL,lease_expires_at=NULL,next_attempt_at=?,updated_at=?
WHERE status='RUNNING' AND lease_expires_at<=?
""",
                now,
                now,
                now);
        jdbc.update(
                """
UPDATE certificate_reference_operations SET status='EXPIRED',failure_code='CERTIFICATE_EXPIRED',
 encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,
 lease_token=NULL,lease_expires_at=NULL,completed_at=?,updated_at=?
WHERE status='QUEUED' AND expires_at<=?
""",
                now,
                now,
                now);
        jdbc.update(
                """
UPDATE certificate_reference_operations SET encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,
 updated_at=? WHERE status IN ('UNKNOWN','ACTION_REQUIRED') AND expires_at<=? AND lease_token IS NULL AND encrypted_payload IS NOT NULL
""",
                now,
                now);
        jdbc.update(
                """
UPDATE certificate_reference_command_outbox SET status='PENDING',lease_token=NULL,
 lease_expires_at=NULL,next_attempt_at=? WHERE status='PROCESSING' AND lease_expires_at<=?
""",
                now,
                now);
    }

    private CertificateReferenceOperation operation(ResultSet r, int n) throws SQLException {
        return new CertificateReferenceOperation(
                r.getObject("id", UUID.class),
                r.getLong("user_id"),
                r.getLong("external_connection_id"),
                r.getString("source_organization"),
                r.getString("organization"),
                r.getObject("idempotency_key", UUID.class),
                r.getString("request_hash"),
                r.getString("connection_hash"),
                CertificateReferenceStatus.valueOf(r.getString("status")),
                r.getBoolean("already_connected"),
                r.getString("failure_code"),
                r.getObject("created_at", LocalDateTime.class),
                r.getObject("expires_at", LocalDateTime.class),
                r.getObject("provider_request_started_at", LocalDateTime.class),
                r.getObject("completed_at", LocalDateTime.class));
    }
}
