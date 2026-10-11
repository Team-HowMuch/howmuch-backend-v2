package com.amateur.howmuchbackend.application.certificate.service;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.*;

import com.amateur.howmuchbackend.application.certificate.model.CertificateClaim;
import com.amateur.howmuchbackend.application.port.in.certificate.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.*;
import com.amateur.howmuchbackend.application.port.in.certificate.result.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.certificate.CertificateSession;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateClaimService
        implements IssueCertificateQrUseCase,
                ReserveCertificateClaimUseCase,
                InspectCertificateClaimUseCase {
    private final CertificateSessionStorePort sessions;
    private final CertificateClaimStorePort claims;
    private final CertificateCapabilityPort capabilities;
    private final CertificateRequestLimitPort limits;
    private final CurrentActorPort actor;
    private final Clock clock;
    private final com.amateur.howmuchbackend.application.port.out.user.LoadUserPort users;

    private CertificateSession pc(UUID id, String pc) {
        var s =
                sessions.find(id, true)
                        .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
        if (!capabilities.matches(pc, s.pcHash()))
            throw ApplicationException.of(CERTIFICATE_NOT_FOUND);
        return s;
    }

    private void active(CertificateSession s, LocalDateTime now) {
        if (!s.expiresAt().isAfter(now)) throw ApplicationException.of(CERTIFICATE_EXPIRED);
    }

    private CertificateClaimResult result(CertificateClaim c) {
        return new CertificateClaimResult(
                c.id(),
                c.status(),
                c.status().equals("RESERVED") ? capabilities.confirmationCode(c.id()) : null,
                c.expiresAt());
    }

    @Transactional
    public CertificateQrResult issue(UUID id, String pc) {
        var s = pc(id, pc);
        var now = LocalDateTime.now(clock);
        active(s, now);
        if (s.status().equals("CLAIM_RESERVED")) {
            var c =
                    claims.current(id)
                            .orElseThrow(() -> ApplicationException.of(CERTIFICATE_CONFLICT));
            if (c.expiresAt().isAfter(now)) throw ApplicationException.of(CERTIFICATE_CONFLICT);
            claims.close(c, "EXPIRED", now);
        } else if (!s.status().equals("READY_FOR_CLAIM"))
            throw ApplicationException.of(CERTIFICATE_CONFLICT);
        return rotate(s, now);
    }

    private CertificateQrResult rotate(CertificateSession s, LocalDateTime now) {
        String raw = capabilities.mint();
        long generation = s.claimGeneration() + 1;
        var expiry =
                now.plusSeconds(60).isBefore(s.expiresAt()) ? now.plusSeconds(60) : s.expiresAt();
        claims.issueQr(s.id(), capabilities.hash(raw), generation, expiry, now);
        return new CertificateQrResult(s.id(), raw, generation, expiry);
    }

    @Transactional
    public CertificateClaimResult reserve(ReserveCertificateClaimCommand c) {
        long user = actor.getCurrentActor().userId();
        if (!limits.acquire(capabilities.hash("claim:" + user)))
            throw ApplicationException.of(CERTIFICATE_RATE_LIMITED);
        var s =
                sessions.find(c.sessionId(), true)
                        .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
        users.findByIdForUpdate(user)
                .filter(com.amateur.howmuchbackend.domain.user.User::active)
                .orElseThrow(
                        () ->
                                ApplicationException.of(
                                        com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode
                                                .USER_NOT_FOUND));
        var now = LocalDateTime.now(clock);
        active(s, now);
        var existing = claims.find(s.id(), user, c.idempotencyKey());
        if (existing.isPresent()) {
            var claim = existing.get();
            if (claim.status().equals("RESERVED") && !claim.expiresAt().isAfter(now))
                throw ApplicationException.of(CERTIFICATE_EXPIRED);
            return result(claim);
        }
        if (!s.status().equals("READY_FOR_CLAIM") || !capabilities.matches(c.qrToken(), s.qrHash()))
            throw ApplicationException.of(CERTIFICATE_NOT_FOUND);
        if (!s.qrExpiresAt().isAfter(now)) throw ApplicationException.of(CERTIFICATE_EXPIRED);
        var expiry =
                now.plusSeconds(60).isBefore(s.expiresAt()) ? now.plusSeconds(60) : s.expiresAt();
        var claim =
                new CertificateClaim(
                        UUID.randomUUID(),
                        s.id(),
                        user,
                        s.claimGeneration(),
                        c.idempotencyKey(),
                        "RESERVED",
                        expiry);
        claims.reserve(claim, capabilities.hash(capabilities.confirmationCode(claim.id())), now);
        return result(claim);
    }

    @Transactional(readOnly = true)
    public CertificateClaimResult inspect(UUID id, String pc) {
        var s =
                sessions.find(id, false)
                        .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
        if (!capabilities.matches(pc, s.pcHash()))
            throw ApplicationException.of(CERTIFICATE_NOT_FOUND);
        var c =
                claims.current(id)
                        .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
        if (!c.expiresAt().isAfter(LocalDateTime.now(clock)))
            throw ApplicationException.of(CERTIFICATE_EXPIRED);
        return result(c);
    }

    @Transactional
    public void reject(UUID id, String pc, UUID claimId) {
        var s = pc(id, pc);
        var now = LocalDateTime.now(clock);
        active(s, now);
        var c =
                claims.current(id)
                        .filter(claim -> claim.id().equals(claimId))
                        .orElseThrow(() -> ApplicationException.of(CERTIFICATE_CONFLICT));
        claims.close(c, c.expiresAt().isAfter(now) ? "REJECTED" : "EXPIRED", now);
        rotate(s, now); // no raw token is disclosed here; PC obtains a fresh QR explicitly.
    }
}
