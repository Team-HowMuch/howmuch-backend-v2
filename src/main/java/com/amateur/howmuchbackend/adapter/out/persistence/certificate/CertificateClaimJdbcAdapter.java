package com.amateur.howmuchbackend.adapter.out.persistence.certificate;

import com.amateur.howmuchbackend.application.certificate.model.CertificateClaim;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateClaimStorePort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateClaimJdbcAdapter implements CertificateClaimStorePort {
    private final JdbcTemplate jdbc;

    public CertificateClaimJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private CertificateClaim claim(ResultSet r, int n) throws SQLException {
        return new CertificateClaim(
                r.getObject("id", UUID.class),
                r.getObject("session_id", UUID.class),
                r.getLong("claimant_user_id"),
                r.getLong("claim_generation"),
                r.getObject("idempotency_key", UUID.class),
                r.getString("status"),
                r.getObject("expires_at", LocalDateTime.class));
    }

    public Optional<CertificateClaim> findById(UUID session, UUID claimId) {
        return jdbc
                .query(
                        "SELECT * FROM certificate_registration_claims WHERE session_id=? AND id=?",
                        this::claim,
                        session,
                        claimId)
                .stream()
                .findFirst();
    }

    public Optional<CertificateClaim> current(UUID session) {
        return jdbc
                .query(
                        "SELECT c.* FROM certificate_registration_claims c JOIN"
                                + " certificate_registration_sessions s ON s.current_claim_id=c.id"
                                + " WHERE s.id=?",
                        this::claim,
                        session)
                .stream()
                .findFirst();
    }

    public Optional<CertificateClaim> find(UUID session, long user, UUID key) {
        return jdbc
                .query(
                        "SELECT * FROM certificate_registration_claims WHERE session_id=? AND"
                                + " claimant_user_id=? AND idempotency_key=?",
                        this::claim,
                        session,
                        user,
                        key)
                .stream()
                .findFirst();
    }

    public void issueQr(
            UUID session, String hash, long generation, LocalDateTime expiry, LocalDateTime now) {
        jdbc.update(
                "UPDATE certificate_registration_sessions SET"
                    + " status='READY_FOR_CLAIM',claim_generation=?,qr_token_hash=?,qr_expires_at=?,current_claim_id=NULL,updated_at=?"
                    + " WHERE id=?",
                generation,
                hash,
                expiry,
                now,
                session);
    }

    public void reserve(CertificateClaim c, String hash, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO"
                    + " certificate_registration_claims(id,session_id,claimant_user_id,claim_generation,idempotency_key,verification_code_hash,status,expires_at,created_at)"
                    + " VALUES(?,?,?,?,?,?,'RESERVED',?,?)",
                c.id(),
                c.sessionId(),
                c.userId(),
                c.generation(),
                c.idempotencyKey(),
                hash,
                c.expiresAt(),
                now);
        jdbc.update(
                "UPDATE certificate_registration_sessions SET"
                    + " status='CLAIM_RESERVED',current_claim_id=?,qr_token_hash=NULL,qr_expires_at=NULL,updated_at=?"
                    + " WHERE id=?",
                c.id(),
                now,
                c.sessionId());
    }

    public void close(CertificateClaim c, String status, LocalDateTime now) {
        if (!Set.of("REJECTED", "EXPIRED").contains(status))
            throw new IllegalArgumentException("Invalid claim closure");
        jdbc.update(
                "UPDATE certificate_registration_claims SET status=?,finished_at=? WHERE id=? AND"
                        + " status='RESERVED'",
                status,
                now,
                c.id());
    }
}
