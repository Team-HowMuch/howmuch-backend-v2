package com.amateur.howmuchbackend.adapter.out.persistence.certificate;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.*;

import com.amateur.howmuchbackend.adapter.out.codef.CodefFailureMapper;
import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.certificate.CertificateSession;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateRegistrationJdbcAdapter
        implements CertificateSessionStorePort,
                CertificateUploadStorePort,
                CertificateCommandOutboxPort {
    private final JdbcTemplate jdbc;

    public CertificateRegistrationJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countActive(LocalDateTime now) {
        // Serializes only the quota check + session insert, inside the caller's transaction.
        jdbc.execute("SELECT pg_advisory_xact_lock(74104680222178)");
        return jdbc.queryForObject(
                "SELECT count(*) FROM certificate_registration_sessions WHERE expires_at>? AND"
                        + " approval_id IS NULL AND status NOT IN ('CANCELLED','EXPIRED','FAILED')",
                Long.class,
                now);
    }

    public void create(
            UUID id,
            String org,
            String hash,
            UUID upload,
            LocalDateTime now,
            LocalDateTime expiry) {
        jdbc.update(
                "INSERT INTO"
                    + " certificate_registration_sessions(id,organization_code,status,pc_capability_hash,upload_ref,expires_at,created_at,updated_at)"
                    + " VALUES(?,?,'CREATED',?,?,?,?,?)",
                id,
                org,
                hash,
                upload,
                expiry,
                now,
                now);
    }

    public Optional<CertificateSession> find(UUID id, boolean lock) {
        return jdbc
                .query(
                        "SELECT * FROM certificate_registration_sessions WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        this::session,
                        id)
                .stream()
                .findFirst();
    }

    private CertificateSession session(ResultSet r, int n) throws SQLException {
        return new CertificateSession(
                r.getObject("id", UUID.class),
                r.getLong("registration_version"),
                r.getString("organization_code"),
                r.getString("status"),
                r.getString("pc_capability_hash"),
                r.getObject("upload_ref", UUID.class),
                r.getObject("connection_ref", UUID.class),
                r.getLong("claim_generation"),
                r.getString("qr_token_hash"),
                r.getObject("qr_expires_at", LocalDateTime.class),
                r.getObject("current_claim_id", UUID.class),
                r.getObject("approval_id", UUID.class),
                (Long) r.getObject("approved_user_id"),
                r.getObject("ownership_verification_ref", UUID.class),
                r.getObject("expires_at", LocalDateTime.class),
                failureCode(r.getString("last_failure_code")));
    }

    private String failureCode(String storedCode) {
        if (storedCode != null && storedCode.matches("CF-[0-9]{5}"))
            return CodefFailureMapper.status(
                            storedCode, ExternalConnectionStatusCode.CODEF_UNMAPPED_ERROR)
                    .getCode();
        return storedCode;
    }

    public void submit(UUID id, LocalDateTime now) {
        if (jdbc.update(
                        "UPDATE certificate_registration_sessions SET"
                            + " status='READY_FOR_CLAIM',claim_generation=1,qr_token_hash=repeat('0',64),qr_expires_at=least(expires_at,created_at"
                            + " + interval '1 microsecond'),updated_at=? WHERE id=? AND"
                            + " status='CREATED'",
                        now,
                        id)
                != 1) throw ApplicationException.of(CERTIFICATE_CONFLICT);
    }

    public void cancel(UUID id, LocalDateTime now) {
        jdbc.update(
                "UPDATE certificate_registration_claims SET status='CANCELLED',finished_at=? WHERE"
                        + " session_id=? AND status='RESERVED'",
                now,
                id);
        jdbc.update(
                "UPDATE certificate_registration_sessions SET"
                    + " status='CANCELLED',qr_token_hash=NULL,qr_expires_at=NULL,current_claim_id=NULL,updated_at=?"
                    + " WHERE id=?",
                now,
                id);
    }

    public void issue(UUID id, UUID session, String hash, LocalDateTime now, LocalDateTime expiry) {
        jdbc.update(
                "INSERT INTO"
                    + " certificate_upload_secrets(upload_ref,session_id,upload_capability_hash,status,expires_at,created_at,updated_at)"
                    + " VALUES(?,?,?,'ISSUED',?,?,?)",
                id,
                session,
                hash,
                expiry,
                now,
                now);
    }

    public boolean accepts(UUID id, String hash, LocalDateTime now) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM certificate_upload_secrets WHERE upload_ref=?"
                            + " AND upload_capability_hash=? AND status='ISSUED' AND expires_at>?)",
                        Boolean.class,
                        id,
                        hash,
                        now));
    }

    public boolean store(UUID id, String hash, CertificateEnvelope e, LocalDateTime now) {
        return jdbc.update(
                        "UPDATE certificate_upload_secrets SET"
                            + " status='STORED',encrypted_payload=?,wrapped_data_key=?,encryption_key_id=?,stored_at=?,updated_at=?"
                            + " WHERE upload_ref=? AND upload_capability_hash=? AND status='ISSUED'"
                            + " AND expires_at>?",
                        e.payload(),
                        e.wrappedKey(),
                        e.keyId(),
                        now,
                        now,
                        id,
                        hash,
                        now)
                == 1;
    }

    public CertificateEnvelope load(UUID id, LocalDateTime now) {
        return jdbc
                .query(
                        "SELECT encrypted_payload,wrapped_data_key,encryption_key_id FROM"
                            + " certificate_upload_secrets WHERE upload_ref=? AND status='STORED'"
                            + " AND expires_at>?",
                        (r, n) ->
                                new CertificateEnvelope(
                                        r.getBytes(1), r.getBytes(2), r.getString(3)),
                        id,
                        now)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(CERTIFICATE_EXPIRED));
    }

    public void clear(UUID id, String status, LocalDateTime now) {
        if (!Set.of("CONSUMED", "CANCELLED", "EXPIRED").contains(status))
            throw new IllegalArgumentException("Invalid upload removal state");
        jdbc.update(
                "UPDATE certificate_upload_secrets SET"
                    + " status=?,encrypted_payload=NULL,wrapped_data_key=NULL,encryption_key_id=NULL,cleared_at=?,updated_at=?"
                    + " WHERE upload_ref=? AND status IN ('ISSUED','STORED') AND (?<>'CONSUMED' OR"
                    + " status='STORED')",
                status,
                now,
                now,
                id,
                status);
    }

    public void enqueue(CertificateSession s, String type, LocalDateTime now) {
        if (!Set.of("REGISTER", "CANCEL").contains(type))
            throw new IllegalArgumentException("Unsupported command");
        jdbc.update(
                "INSERT INTO"
                    + " certificate_command_outbox(message_id,session_id,session_version,command_type,upload_ref,status,next_attempt_at,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,'PENDING',?,?,?) ON"
                    + " CONFLICT(session_id,command_type,session_version) DO NOTHING",
                UUID.randomUUID(),
                s.id(),
                s.version(),
                type,
                "REGISTER".equals(type) ? s.uploadRef() : null,
                now,
                now,
                now);
    }
}
