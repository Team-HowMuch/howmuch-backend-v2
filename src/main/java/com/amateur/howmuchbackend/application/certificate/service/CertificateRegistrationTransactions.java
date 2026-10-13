package com.amateur.howmuchbackend.application.certificate.service;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.*;

import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;
import com.amateur.howmuchbackend.application.certificate.policy.CertificateRegistrationPolicy;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.certificate.CertificateSession;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateRegistrationTransactions {
    private final CertificateSessionStorePort sessions;
    private final CertificateUploadStorePort uploads;
    private final CertificateCommandOutboxPort outbox;
    private final CertificateCapabilityPort capabilities;
    private final CertificateRegistrationPolicy policy;
    private final Clock clock;
    private final CertificateConsentStorePort consents;
    private final CertificatePairingStorePort pairings;

    @Transactional
    public void create(
            UUID id,
            String organization,
            String pcHash,
            UUID upload,
            String uploadHash,
            LocalDateTime now,
            LocalDateTime expires) {
        policy.capacity(sessions.countActive(now));
        sessions.create(id, organization, pcHash, upload, now, expires);
        uploads.issue(upload, id, uploadHash, now, expires);
    }

    @Transactional
    public void submit(UUID id, String pc, String uploadHash, CertificateEnvelope envelope) {
        var s = authorized(id, pc, true);
        var now = LocalDateTime.now(clock);
        policy.active(s, now);
        if (!"CREATED".equals(s.status())
                || !uploads.store(s.uploadRef(), uploadHash, envelope, now))
            throw ApplicationException.of(CERTIFICATE_CONFLICT);
        var pairing = pairings.findBySession(id);
        if (pairing.isPresent()) {
            var p = pairing.get();
            if (p.userId() == null || p.cancelledAt() != null || !p.expiresAt().isAfter(now))
                throw ApplicationException.of(CERTIFICATE_EXPIRED);
            sessions.submitForAuthentication(id, now);
            outbox.enqueue(s, "REGISTER", now);
        } else sessions.submit(id, now);
    }

    @Transactional(readOnly = true)
    public CertificateSession get(UUID id, String pc) {
        return authorized(id, pc, false);
    }

    @Transactional
    public void cancel(UUID id, String pc) {
        var s = authorized(id, pc, true);
        cancelSession(s);
    }

    @Transactional
    public void cancelPaired(UUID id, String pc) {
        var p =
                pairings.find(id, false)
                        .filter(v -> capabilities.matches(pc, v.pcHash()))
                        .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
        if (p.sessionId() != null) {
            var s =
                    sessions.find(p.sessionId(), true)
                            .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
            cancelSession(s);
        }
    }

    private void cancelSession(CertificateSession s) {
        var id = s.id();
        if (s.status().equals("UNKNOWN") && pairings.findBySession(s.id()).isPresent())
            throw ApplicationException.of(CERTIFICATE_CONFLICT);
        if (s.approved()) throw ApplicationException.of(CERTIFICATE_CONFLICT);
        if (s.terminal()) return;
        if (consents.find(id).isPresent()) throw ApplicationException.of(CERTIFICATE_CONFLICT);
        var now = LocalDateTime.now(clock);
        sessions.cancel(id, now);
        uploads.clear(s.uploadRef(), "CANCELLED", now);
        outbox.enqueue(s, "CANCEL", now);
    }

    private CertificateSession authorized(UUID id, String pc, boolean lock) {
        return sessions.find(id, lock)
                .filter(s -> capabilities.matches(pc, s.pcHash()))
                .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
    }
}
