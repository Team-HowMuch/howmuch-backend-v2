package com.amateur.howmuchbackend.adapter.out.persistence.external;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.external.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.application.port.out.external.AdditionalAuthenticationStorePort;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.codef.additional-auth",
        name = "enabled",
        havingValue = "true")
public class AdditionalAuthenticationJdbcAdapter implements AdditionalAuthenticationStorePort {
    private final JdbcTemplate jdbc;
    private static final String SELECT =
            """
            SELECT a.*,j.session_version,j.upload_ref,j.deadline_at,j.received_at,
                   j.lease_generation original_generation,j.organization_code original_organization
            FROM codef_authentication_challenges a
            LEFT JOIN certificate_provider_command_inbox j ON j.message_id=a.certificate_command_id
            """;

    public boolean tryAcquireOperationAdmission(long user, String organization) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("Finance operation admission requires a transaction");
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class,
                "finance-authentication:" + user + ":" + organization));
    }

    public void create(
            UUID id,
            long user,
            CertificateExecution e,
            AdditionalAuthenticationRequest request,
            CertificateEnvelope payload,
            LocalDateTime expires,
            LocalDateTime now) {
        int changed =
                jdbc.update(
                        """
UPDATE certificate_provider_command_inbox SET status='WAITING_AUTH',last_failure_code='ADDITIONAL_AUTH_REQUIRED',
 lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,next_attempt_at=NULL,updated_at=?
WHERE message_id=? AND status='PROCESSING' AND lease_token=? AND lease_generation=?
 AND provider_request_started_at IS NOT NULL AND lease_expires_at>?
""",
                        now,
                        e.command().messageId(),
                        e.leaseToken(),
                        e.generation(),
                        now);
        if (changed != 1) throw new IllegalStateException("Certificate execution lease lost");
        jdbc.update(
                """
INSERT INTO codef_authentication_challenges(id,user_id,operation_type,operation_id,organization_code,
 certificate_command_id,method,masked_destination,status,encrypted_payload,wrapped_data_key,encryption_key_id,
 expires_at,created_at,updated_at)
VALUES(?,?,'CERTIFICATE_REGISTER',?,?,?,'SMS',?,'WAITING_INPUT',?,?,?,?,?,?)
""",
                id,
                user,
                e.command().sessionId(),
                e.command().organization(),
                e.command().messageId(),
                request.maskedDestination(),
                payload.payload(),
                payload.wrappedKey(),
                payload.keyId(),
                expires,
                now,
                now);
    }

    public void createRetry(
            AdditionalAuthenticationChallenge previous,
            UUID id,
            AdditionalAuthenticationRequest request,
            CertificateEnvelope payload,
            LocalDateTime expires,
            LocalDateTime now) {
        int inserted =
                jdbc.update(
                        """
INSERT INTO codef_authentication_challenges(id,user_id,operation_type,operation_id,
 generation,organization_code,certificate_command_id,connection_id,method,masked_destination,
 status,encrypted_payload,wrapped_data_key,encryption_key_id,expires_at,created_at,updated_at)
SELECT ?,user_id,operation_type,operation_id,generation+1,organization_code,
 certificate_command_id,connection_id,'SMS',?,'WAITING_INPUT',?,?,?,?,?,?
FROM codef_authentication_challenges WHERE id=? AND status='FAILED'
 AND failure_code='AUTH_SMS_RETRY_REQUIRED' AND cancel_requested_at IS NULL
""",
                        id,
                        request.maskedDestination(),
                        payload.payload(),
                        payload.wrappedKey(),
                        payload.keyId(),
                        expires,
                        now,
                        now,
                        previous.id());
        if (inserted != 1) throw new IllegalStateException("Authentication retry source lost");
    }

    public Optional<AdditionalAuthenticationChallenge> find(UUID id, boolean lock) {
        return jdbc
                .query(SELECT + " WHERE a.id=?" + (lock ? " FOR UPDATE OF a" : ""), this::map, id)
                .stream()
                .findFirst();
    }

    public Optional<AdditionalAuthenticationChallenge> current(UUID session) {
        return jdbc
                .query(
                        SELECT + " WHERE a.operation_id=? ORDER BY a.generation DESC LIMIT 1",
                        this::map,
                        session)
                .stream()
                .findFirst();
    }

    public List<AdditionalAuthenticationChallenge> activeForUser(long user, LocalDateTime now) {
        return jdbc.query(
                SELECT
                        + " WHERE a.user_id=? AND a.status IN"
                        + " ('WAITING_INPUT','SUBMITTED','PROCESSING') AND a.expires_at>? ORDER BY"
                        + " a.created_at LIMIT 20",
                this::map,
                user,
                now);
    }

    public void createOperation(
            UUID id,
            long user,
            UUID operationId,
            FinanceOperation operation,
            AdditionalAuthenticationRequest request,
            CertificateEnvelope payload,
            LocalDateTime expires,
            LocalDateTime now) {
        jdbc.update(
                """
INSERT INTO codef_authentication_challenges(id,user_id,operation_type,operation_id,organization_code,connection_id,
    method,masked_destination,status,encrypted_payload,wrapped_data_key,encryption_key_id,expires_at,created_at,updated_at)
VALUES(?,?,?,?,?,?,'SMS',?,'WAITING_INPUT',?,?,?,?,?,?)
""",
                id,
                user,
                operation.type(),
                operationId,
                operation.organization(),
                operation.connectionId(),
                request.maskedDestination(),
                payload.payload(),
                payload.wrappedKey(),
                payload.keyId(),
                expires,
                now,
                now);
    }

    public Optional<AdditionalAuthenticationChallenge> pending(
            long user, String organization, LocalDateTime now) {
        return jdbc
                .query(
                        SELECT
                                + " WHERE a.user_id=? AND a.organization_code=? AND a.status IN"
                                + " ('WAITING_INPUT','SUBMITTED','PROCESSING') ORDER BY"
                                + " a.created_at DESC LIMIT 1",
                        this::map,
                        user,
                        organization)
                .stream()
                .findFirst();
    }

    public Optional<AdditionalAuthenticationChallenge> recentResult(
            long user, Long connection, String organization, LocalDateTime now) {
        return jdbc
                .query(
                        SELECT
                                + " WHERE a.user_id=? AND a.connection_id=? AND"
                                + " a.organization_code=? AND a.operation_type='ACCOUNT_LIST' AND"
                                + " a.status='SUCCEEDED' AND a.result_expires_at>? ORDER BY"
                                + " a.completed_at DESC LIMIT 1",
                        this::map,
                        user,
                        connection,
                        organization,
                        now)
                .stream()
                .findFirst();
    }

    public Optional<CertificateEnvelope> result(UUID id, LocalDateTime now) {
        return jdbc
                .query(
                        "SELECT encrypted_result,wrapped_result_key,result_key_id FROM"
                            + " codef_authentication_challenges WHERE id=? AND result_expires_at>?"
                            + " AND status='SUCCEEDED'",
                        (r, n) ->
                                new CertificateEnvelope(
                                        r.getBytes(1), r.getBytes(2), r.getString(3)),
                        id,
                        now)
                .stream()
                .findFirst();
    }

    public void finishWithResult(
            AdditionalAuthenticationChallenge c, CertificateEnvelope result, LocalDateTime now) {
        int changed =
                jdbc.update(
                        """
UPDATE codef_authentication_challenges SET status='SUCCEEDED',completed_at=?,updated_at=?,
 encrypted_result=?,wrapped_result_key=?,result_key_id=?,result_expires_at=?,
 encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,cleared_at=?,
 lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL
WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_generation=? AND cancel_requested_at IS NULL
""",
                        now,
                        now,
                        result.payload(),
                        result.wrappedKey(),
                        result.keyId(),
                        now.plusMinutes(5),
                        now,
                        c.id(),
                        c.leaseToken(),
                        c.leaseGeneration());
        if (changed != 1) throw new IllegalStateException("Authentication lease lost");
    }

    public void submit(UUID id, UUID submission, CertificateEnvelope payload, LocalDateTime now) {
        try {
            if (jdbc.update(
                            """
UPDATE codef_authentication_challenges SET status='SUBMITTED',submission_id=?,submitted_at=?,
 encrypted_payload=?,wrapped_data_key=?,encryption_key_id=?,updated_at=?
WHERE id=? AND status='WAITING_INPUT' AND cancel_requested_at IS NULL AND expires_at>?
""",
                            submission,
                            now,
                            payload.payload(),
                            payload.wrappedKey(),
                            payload.keyId(),
                            now,
                            id,
                            now)
                    != 1) throw new IllegalStateException("Authentication acceptance lost");
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw com.amateur.howmuchbackend.common.exception.ApplicationException.of(
                    com.amateur.howmuchbackend.domain.external.status
                            .AdditionalAuthenticationStatusCode.FINANCE_AUTHENTICATION_CONFLICT);
        }
    }

    public Optional<AdditionalAuthenticationChallenge> claim(LocalDateTime now) {
        var ids =
                jdbc.query(
                        """
WITH candidate AS (SELECT id FROM codef_authentication_challenges WHERE status='SUBMITTED'
 AND cancel_requested_at IS NULL AND expires_at>? ORDER BY submitted_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
UPDATE codef_authentication_challenges a SET status='PROCESSING',attempt_count=attempt_count+1,
 lease_generation=lease_generation+1,lease_token=?,processing_started_at=?,
 lease_expires_at=least(expires_at,?::timestamp),updated_at=? FROM candidate c WHERE a.id=c.id RETURNING a.id
""",
                        (r, n) -> r.getObject(1, UUID.class),
                        now,
                        UUID.randomUUID(),
                        now,
                        now.plusMinutes(1),
                        now);
        return ids.isEmpty() ? Optional.empty() : find(ids.getFirst(), false);
    }

    public boolean start(AdditionalAuthenticationChallenge c, LocalDateTime now) {
        return jdbc.update(
                        """
UPDATE codef_authentication_challenges SET provider_request_started_at=?,updated_at=?
WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_generation=?
 AND provider_request_started_at IS NULL AND cancel_requested_at IS NULL AND lease_expires_at>? AND expires_at>?
""",
                        now,
                        now,
                        c.id(),
                        c.leaseToken(),
                        c.leaseGeneration(),
                        now,
                        now)
                == 1;
    }

    public void finish(
            AdditionalAuthenticationChallenge c, String status, String code, LocalDateTime now) {
        if (code != null && code.matches("CF-[0-9]{5}"))
            code =
                    com.amateur.howmuchbackend.adapter.out.codef.CodefFailureMapper.status(
                                    code,
                                    com.amateur.howmuchbackend.domain.external.status
                                            .ExternalConnectionStatusCode.CODEF_UNMAPPED_ERROR)
                            .getCode();
        jdbc.update(
                """
UPDATE codef_authentication_challenges SET status=?,failure_code=?,completed_at=?,updated_at=?,
 encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,cleared_at=coalesce(cleared_at,?),
 lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL
WHERE id=? AND status IN ('WAITING_INPUT','SUBMITTED','PROCESSING') AND lease_generation=?
""",
                status,
                code,
                now,
                now,
                now,
                c.id(),
                c.leaseGeneration());
    }

    public void cancelForSession(UUID session, LocalDateTime now) {
        // No continuation is automatically replayed after the provider boundary.
        jdbc.update(
                """
UPDATE codef_authentication_challenges SET cancel_requested_at=coalesce(cancel_requested_at,?),
 status=CASE WHEN status='PROCESSING' AND provider_request_started_at IS NOT NULL THEN status ELSE 'CANCELLED' END,
 failure_code=CASE WHEN status='PROCESSING' AND provider_request_started_at IS NOT NULL THEN NULL ELSE 'AUTH_CANCELLED' END,
 completed_at=CASE WHEN status='PROCESSING' AND provider_request_started_at IS NOT NULL THEN NULL ELSE ? END,
 lease_token=CASE WHEN status='PROCESSING' AND provider_request_started_at IS NOT NULL THEN lease_token ELSE NULL END,
 processing_started_at=CASE WHEN status='PROCESSING' AND provider_request_started_at IS NOT NULL THEN processing_started_at ELSE NULL END,
 lease_expires_at=CASE WHEN status='PROCESSING' AND provider_request_started_at IS NOT NULL THEN lease_expires_at ELSE NULL END,
 encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,cleared_at=coalesce(cleared_at,?),updated_at=?
WHERE operation_id=? AND status IN ('WAITING_INPUT','SUBMITTED','PROCESSING')
""",
                now,
                now,
                now,
                now,
                session);
        jdbc.update(
                """
UPDATE certificate_provider_command_inbox j SET status='FAILED',last_failure_code='CONTRACT_CONFLICT',
 completed_at=?,updated_at=?
WHERE j.session_id=? AND j.status='WAITING_AUTH' AND EXISTS(
 SELECT 1 FROM codef_authentication_challenges a WHERE a.certificate_command_id=j.message_id
  AND a.status='CANCELLED')
""",
                now,
                now,
                session);
    }

    public void clearExpiredResults(LocalDateTime now) {
        jdbc.update(
                "UPDATE codef_authentication_challenges SET"
                    + " encrypted_result=NULL,wrapped_result_key=NULL,result_key_id=NULL,result_expires_at=NULL,updated_at=?"
                    + " WHERE result_expires_at<=?",
                now,
                now);
    }

    public List<UUID> recoverable(LocalDateTime now) {
        return jdbc.query(
                """
SELECT id FROM codef_authentication_challenges WHERE status IN ('WAITING_INPUT','SUBMITTED','PROCESSING')
 AND (expires_at<=? OR (status='PROCESSING' AND lease_expires_at<=?) OR cancel_requested_at IS NOT NULL)
ORDER BY expires_at,id LIMIT 20
""",
                (r, n) -> r.getObject(1, UUID.class),
                now,
                now);
    }

    public void requeue(AdditionalAuthenticationChallenge c, LocalDateTime now) {
        jdbc.update(
                """
UPDATE codef_authentication_challenges SET status='SUBMITTED',lease_token=NULL,processing_started_at=NULL,
 lease_expires_at=NULL,updated_at=? WHERE id=? AND status='PROCESSING' AND lease_token=?
 AND lease_generation=? AND provider_request_started_at IS NULL
 AND cancel_requested_at IS NULL AND expires_at>?
""",
                now,
                c.id(),
                c.leaseToken(),
                c.leaseGeneration(),
                now);
    }

    private AdditionalAuthenticationChallenge map(ResultSet r, int n) throws SQLException {
        byte[] payload = r.getBytes("encrypted_payload");
        UUID session = r.getObject("operation_id", UUID.class);
        ReceiveCertificateCommand command =
                r.getObject("certificate_command_id") == null
                        ? null
                        : new ReceiveCertificateCommand(
                                r.getObject("certificate_command_id", UUID.class),
                                session,
                                r.getLong("session_version"),
                                "REGISTER",
                                r.getString("original_organization"),
                                r.getObject("upload_ref", UUID.class),
                                r.getObject("deadline_at", LocalDateTime.class),
                                r.getObject("received_at", LocalDateTime.class));
        return new AdditionalAuthenticationChallenge(
                r.getObject("id", UUID.class),
                r.getLong("user_id"),
                session,
                r.getString("organization_code"),
                r.getLong("generation"),
                r.getString("status"),
                r.getString("masked_destination"),
                r.getString("failure_code"),
                r.getObject("submission_id", UUID.class),
                r.getObject("lease_token", UUID.class),
                r.getLong("lease_generation"),
                r.getTimestamp("provider_request_started_at") != null,
                r.getTimestamp("cancel_requested_at") != null,
                r.getObject("expires_at", LocalDateTime.class),
                r.getObject("lease_expires_at", LocalDateTime.class),
                payload == null
                        ? null
                        : new CertificateEnvelope(
                                payload,
                                r.getBytes("wrapped_data_key"),
                                r.getString("encryption_key_id")),
                r.getObject("certificate_command_id") == null
                        ? null
                        : new CertificateExecution(command, null, r.getLong("original_generation")),
                r.getString("operation_type"),
                (Long) r.getObject("connection_id"));
    }
}
