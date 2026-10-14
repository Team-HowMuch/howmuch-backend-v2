package com.amateur.howmuchbackend.adapter.out.persistence.certificate;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.CERTIFICATE_CONFLICT;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificatePairingStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificatePairingJdbcAdapter implements CertificatePairingStorePort {
    private final JdbcTemplate jdbc;

    private CertificatePairing map(ResultSet r, int n) throws SQLException {
        return new CertificatePairing(
                r.getObject("id", UUID.class),
                r.getString("pc_capability_hash"),
                r.getString("qr_token_hash"),
                r.getObject("qr_expires_at", LocalDateTime.class),
                r.getObject("expires_at", LocalDateTime.class),
                (Long) r.getObject("paired_user_id"),
                r.getObject("registration_session_id", UUID.class),
                r.getBoolean("authentication_prepared"),
                (Long) r.getObject("existing_connection_id"),
                r.getObject("cancelled_at", LocalDateTime.class));
    }

    public long countActive(LocalDateTime now) {
        // Serialize capacity admission through the surrounding create transaction.
        jdbc.execute("SELECT pg_advisory_xact_lock(74104680222179)");
        return jdbc.queryForObject(
                "SELECT count(*) FROM certificate_pc_pairings WHERE expires_at>? AND cancelled_at"
                        + " IS NULL",
                Long.class,
                now);
    }

    public boolean hasUnresolvedAuthentication(long user, UUID session) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM certificate_pc_pairings p JOIN"
                            + " certificate_registration_sessions s ON"
                            + " s.id=p.registration_session_id WHERE p.paired_user_id=? AND s.id<>?"
                            + " AND (EXISTS(SELECT 1 FROM certificate_provider_command_inbox j"
                            + " WHERE j.session_id=s.id AND j.command_type='REGISTER' AND"
                            + " j.provider_request_started_at IS NOT NULL AND j.status IN"
                            + " ('PROCESSING','UNKNOWN')) OR EXISTS(SELECT 1 FROM"
                            + " certificate_provider_connections c WHERE c.session_id=s.id AND"
                            + " c.status='READY')))",
                        Boolean.class,
                        user,
                        session));
    }

    public void create(CertificatePairing p, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO"
                    + " certificate_pc_pairings(id,pc_capability_hash,qr_token_hash,qr_expires_at,expires_at,created_at)"
                    + " VALUES(?,?,?,?,?,?)",
                p.id(),
                p.pcHash(),
                p.qrHash(),
                p.qrExpiresAt(),
                p.expiresAt(),
                now);
    }

    public Optional<CertificatePairing> find(UUID id, boolean lock) {
        return jdbc
                .query(
                        "SELECT * FROM certificate_pc_pairings WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        this::map,
                        id)
                .stream()
                .findFirst();
    }

    public Optional<CertificatePairing> findBySession(UUID s) {
        return jdbc
                .query(
                        "SELECT * FROM certificate_pc_pairings WHERE registration_session_id=?",
                        this::map,
                        s)
                .stream()
                .findFirst();
    }

    public void pair(UUID id, long user, LocalDateTime now) {
        if (jdbc.update(
                        "UPDATE certificate_pc_pairings SET paired_user_id=?,paired_at=? WHERE id=?"
                                + " AND paired_user_id IS NULL AND cancelled_at IS NULL AND"
                                + " qr_expires_at>?",
                        user,
                        now,
                        id,
                        now)
                != 1) throw ApplicationException.of(CERTIFICATE_CONFLICT);
    }

    public void link(UUID id, UUID session, String registrationPcHash) {
        if (jdbc.update(
                        "UPDATE certificate_pc_pairings SET"
                            + " registration_session_id=?,registration_pc_capability_hash=?,"
                            + " authentication_prepared=FALSE,existing_connection_id=NULL WHERE"
                            + " id=? AND (registration_session_id IS NULL OR EXISTS (SELECT 1 FROM"
                            + " certificate_registration_sessions s WHERE s.id=registration_session_id"
                            + " AND s.status='FAILED' AND s.approval_id IS NULL"
                            + " AND s.current_claim_id IS NULL AND s.approved_user_id IS NULL))"
                            + " AND paired_user_id IS NOT"
                            + " NULL AND cancelled_at IS NULL",
                        session,
                        registrationPcHash,
                        id)
                != 1) throw ApplicationException.of(CERTIFICATE_CONFLICT);
    }

    public void prepare(UUID session, Long connection) {
        if (jdbc.update(
                        "UPDATE certificate_pc_pairings SET"
                                + " authentication_prepared=TRUE,existing_connection_id=? WHERE"
                                + " registration_session_id=? AND NOT authentication_prepared AND"
                                + " cancelled_at IS NULL",
                        connection,
                        session)
                != 1) throw ApplicationException.of(CERTIFICATE_CONFLICT);
    }

    public void cancel(UUID id, LocalDateTime now) {
        jdbc.update(
                "UPDATE certificate_pc_pairings SET cancelled_at=? WHERE id=? AND cancelled_at IS"
                        + " NULL",
                now,
                id);
    }
}
