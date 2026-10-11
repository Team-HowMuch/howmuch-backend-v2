package com.amateur.howmuchbackend.application.certificate.service;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.*;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.DecideCertificateConnectionCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateConnectionDecisionResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.certificate.CertificateSession;
import com.amateur.howmuchbackend.domain.user.User;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateConnectionDecisionService
        implements DecideCertificateConnectionUseCase, GetCertificateConnectionDecisionUseCase {
    private final CertificateSessionStorePort sessions;
    private final CertificateClaimStorePort claims;
    private final CertificateConsentStorePort consents;
    private final CertificateUploadStorePort uploads;
    private final CertificateCommandOutboxPort outbox;
    private final CurrentActorPort actor;
    private final LoadUserPort users;
    private final Clock clock;

    @Transactional
    public CertificateConnectionDecisionResult decide(DecideCertificateConnectionCommand command) {
        var s =
                sessions.find(command.sessionId(), true)
                        .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
        var c = ownedClaim(command.sessionId(), command.claimId());
        var consent = consents.find(s.id()).orElse(null);
        // Duplicate approval remains readable after the short QR/claim deadline.
        if (consent != null) {
            if (!consent.claimId().equals(c.id()) || !command.approved())
                throw ApplicationException.of(CERTIFICATE_CONFLICT);
            return result(s, c, consent);
        }
        if (c.status().equals("REJECTED") && !command.approved()) return result(s, c, null);
        var now = LocalDateTime.now(clock);
        if (!s.expiresAt().isAfter(now) || !c.expiresAt().isAfter(now))
            throw ApplicationException.of(CERTIFICATE_EXPIRED);
        if (!s.status().equals("CLAIM_RESERVED")
                || !c.status().equals("RESERVED")
                || !c.id().equals(s.currentClaimId())
                || c.generation() != s.claimGeneration())
            throw ApplicationException.of(CERTIFICATE_CONFLICT);
        users.findByIdForUpdate(c.userId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        if (command.approved()) {
            if (s.connectionRef() != null) throw ApplicationException.of(CERTIFICATE_CONFLICT);
            consents.approve(
                    new CertificateConsent(
                            UUID.randomUUID(),
                            s.id(),
                            c.id(),
                            c.userId(),
                            c.generation(),
                            now,
                            null),
                    now);
            outbox.enqueue(s, "REGISTER", now);
        } else {
            claims.close(c, "REJECTED", now);
            sessions.cancel(s.id(), now);
            uploads.clear(s.uploadRef(), "CANCELLED", now);
            if (s.connectionRef() != null) outbox.enqueue(s, "CANCEL", now);
        }
        return result(
                sessions.find(s.id(), false).orElseThrow(), c, consents.find(s.id()).orElse(null));
    }

    @Transactional(readOnly = true)
    public CertificateConnectionDecisionResult getDecision(UUID sessionId, UUID claimId) {
        var c = ownedClaim(sessionId, claimId);
        var s =
                sessions.find(sessionId, false)
                        .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
        return result(s, c, consents.find(sessionId).orElse(null));
    }

    private CertificateClaim ownedClaim(UUID session, UUID claim) {
        long user = actor.getCurrentActor().userId();
        return claims.findById(session, claim)
                .filter(c -> c.userId() == user)
                .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
    }

    private CertificateConnectionDecisionResult result(
            CertificateSession s, CertificateClaim c, CertificateConsent consent) {
        var failure = CertificateFailureStatus.from(s.failureCode());
        String status = s.status();
        if (consent == null && c.status().equals("REJECTED")) status = "CANCELLED";
        if (consent == null
                && !c.expiresAt().isAfter(LocalDateTime.now(clock))
                && c.status().equals("RESERVED")) status = "EXPIRED";
        return new CertificateConnectionDecisionResult(
                s.id(),
                c.id(),
                s.organization(),
                status,
                consent == null ? null : consent.connectionId(),
                s.expiresAt(),
                failure == null ? null : failure.getCode(),
                failure == null ? null : failure.getMessage());
    }
}
