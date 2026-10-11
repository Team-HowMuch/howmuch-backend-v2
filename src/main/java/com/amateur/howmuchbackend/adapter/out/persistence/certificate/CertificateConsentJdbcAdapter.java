package com.amateur.howmuchbackend.adapter.out.persistence.certificate;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.CERTIFICATE_CONFLICT;

import com.amateur.howmuchbackend.application.certificate.model.CertificateConsent;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateConsentStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateConsentJdbcAdapter implements CertificateConsentStorePort {
    private final JdbcTemplate jdbc;

    public CertificateConsentJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CertificateConsent> find(UUID session) {
        return jdbc
                .query(
                        "SELECT * FROM certificate_registration_consents WHERE session_id=?",
                        (r, n) ->
                                new CertificateConsent(
                                        r.getObject("id", UUID.class),
                                        r.getObject("session_id", UUID.class),
                                        r.getObject("claim_id", UUID.class),
                                        r.getLong("user_id"),
                                        r.getLong("claim_generation"),
                                        r.getObject("approved_at", LocalDateTime.class),
                                        (Long) r.getObject("external_connection_id")),
                        session)
                .stream()
                .findFirst();
    }

    public void approve(CertificateConsent c, LocalDateTime now) {
        if (jdbc.update(
                        "UPDATE certificate_registration_claims SET status='APPROVED',finished_at=?"
                            + " WHERE id=? AND status='RESERVED' AND expires_at>?",
                        now,
                        c.claimId(),
                        now)
                != 1) throw ApplicationException.of(CERTIFICATE_CONFLICT);
        jdbc.update(
                "INSERT INTO"
                    + " certificate_registration_consents(id,session_id,claim_id,user_id,claim_generation,approved_at)"
                    + " VALUES(?,?,?,?,?,?)",
                c.id(),
                c.sessionId(),
                c.claimId(),
                c.userId(),
                c.generation(),
                c.approvedAt());
        if (jdbc.update(
                        "UPDATE certificate_registration_sessions SET"
                            + " status='SUBMITTED',current_claim_id=NULL,updated_at=? WHERE id=?"
                            + " AND status='CLAIM_RESERVED' AND current_claim_id=? AND"
                            + " claim_generation=? AND expires_at>?",
                        now,
                        c.sessionId(),
                        c.claimId(),
                        c.generation(),
                        now)
                != 1) throw ApplicationException.of(CERTIFICATE_CONFLICT);
    }

    public void bind(CertificateConsent c, UUID ref, long connection, LocalDateTime now) {
        if (jdbc.update(
                        "UPDATE certificate_registration_consents SET"
                            + " external_connection_id=?,completed_at=? WHERE id=? AND completed_at"
                            + " IS NULL",
                        connection,
                        now,
                        c.id())
                != 1) throw ApplicationException.of(CERTIFICATE_CONFLICT);
        if (jdbc.update(
                        "UPDATE certificate_registration_sessions SET"
                            + " status='BOUND',connection_ref=?,approval_id=?,approved_claim_id=?,approved_user_id=?,ownership_verification_ref=?,approved_at=?,last_failure_code=NULL,updated_at=?"
                            + " WHERE id=? AND status='REGISTERING' AND approval_id IS NULL",
                        ref,
                        c.id(),
                        c.claimId(),
                        c.userId(),
                        c.id(),
                        c.approvedAt(),
                        now,
                        c.sessionId())
                != 1) throw ApplicationException.of(CERTIFICATE_CONFLICT);
        if (jdbc.update(
                        "UPDATE certificate_provider_connections SET"
                            + " status='BOUND',approval_id=?,owner_user_id=?,ownership_verification_ref=?,external_connection_id=?,bound_at=?,updated_at=?"
                            + " WHERE connection_ref=? AND status='READY'",
                        c.id(),
                        c.userId(),
                        c.id(),
                        connection,
                        now,
                        now,
                        ref)
                != 1) throw ApplicationException.of(CERTIFICATE_CONFLICT);
    }
}
