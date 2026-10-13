package com.amateur.howmuchbackend.application.certificate.service;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.*;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.CreateCertificateRegistrationCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.result.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
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
public class CertificatePairingService
        implements CreateCertificatePairingUseCase,
                GetCertificatePairingUseCase,
                PairCertificateDeviceUseCase,
                StartPairedCertificateRegistrationUseCase,
                ReservePairedCertificateClaimUseCase,
                CancelCertificatePairingUseCase {
    private final CertificatePairingStorePort pairings;
    private final CertificateCapabilityPort capabilities;
    private final CertificateRequestLimitPort limits;
    private final CurrentActorPort actor;
    private final LoadUserPort users;
    private final CertificateSessionStorePort sessions;
    private final CertificateClaimStorePort claims;
    private final CreateCertificateRegistrationUseCase registrations;
    private final CertificateRegistrationTransactions transactions;
    private final Clock clock;

    @Transactional
    public CertificatePairingResult createPairing(String remoteAddress) {
        if (!limits.acquire(capabilities.hash("pair:" + remoteAddress))
                || pairings.countActive(now()) >= 200)
            throw ApplicationException.of(CERTIFICATE_RATE_LIMITED);
        var time = now();
        var pc = capabilities.mint();
        var qr = capabilities.mint();
        var p =
                new CertificatePairing(
                        UUID.randomUUID(),
                        capabilities.hash(pc),
                        capabilities.hash(qr),
                        time.plusMinutes(2),
                        time.plusMinutes(15),
                        null,
                        null,
                        false,
                        null,
                        null);
        pairings.create(p, time);
        return result(p, pc, qr);
    }

    @Transactional(readOnly = true)
    public CertificatePairingResult getPcPairing(UUID id, String pc) {
        return result(pc(id, pc, false), null, null);
    }

    @Transactional
    public CertificatePairingResult pair(UUID id, String qrToken) {
        long user = actor.getCurrentActor().userId();
        if (!limits.acquire(capabilities.hash("pair-claim:" + user)))
            throw ApplicationException.of(CERTIFICATE_RATE_LIMITED);
        var p = find(id, true);
        if (!capabilities.matches(qrToken, p.qrHash()))
            throw ApplicationException.of(CERTIFICATE_NOT_FOUND);
        active(p);
        if (p.userId() != null) {
            if (p.userId() != user) throw ApplicationException.of(CERTIFICATE_NOT_FOUND);
            return result(p, null, null);
        }
        if (!p.qrExpiresAt().isAfter(now())) throw ApplicationException.of(CERTIFICATE_EXPIRED);
        users.findByIdForUpdate(user)
                .filter(User::active)
                .orElseThrow(
                        () ->
                                ApplicationException.of(
                                        com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode
                                                .USER_NOT_FOUND));
        pairings.pair(id, user, now());
        return result(find(id, false), null, null);
    }

    @Transactional(readOnly = true)
    public CertificatePairingResult getMobilePairing(UUID id) {
        return result(mobile(id), null, null);
    }

    @Transactional
    public CertificateRegistrationResult createRegistration(
            com.amateur.howmuchbackend.application.port.in.certificate.command
                            .StartPairedCertificateRegistrationCommand
                    command) {
        var id = command.pairingId();
        var p = pc(id, command.pcCapability(), true);
        active(p);
        if (p.userId() == null || p.sessionId() != null)
            throw ApplicationException.of(CERTIFICATE_CONFLICT);
        var s =
                registrations.create(
                        new CreateCertificateRegistrationCommand(
                                command.organization(), command.remoteAddress()));
        pairings.link(id, s.id(), capabilities.hash(s.pcCapability()));
        return s;
    }

    @Transactional
    public CertificateClaimResult requestApproval(UUID id) {
        var p = mobile(id);
        active(p);
        if (p.sessionId() == null) throw ApplicationException.of(CERTIFICATE_CONFLICT);
        var s =
                sessions.find(p.sessionId(), true)
                        .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
        if (!s.expiresAt().isAfter(now())) throw ApplicationException.of(CERTIFICATE_EXPIRED);
        var current = claims.current(s.id());
        if (current.isPresent()) {
            var c = current.get();
            if (c.userId() != p.userId()) throw ApplicationException.of(CERTIFICATE_NOT_FOUND);
            if (!c.expiresAt().isAfter(now())) throw ApplicationException.of(CERTIFICATE_EXPIRED);
            return claimResult(c);
        }
        if (!s.status().equals("READY_FOR_CLAIM") || s.connectionRef() == null)
            throw ApplicationException.of(CERTIFICATE_CONFLICT);
        var expiry = s.expiresAt().isBefore(p.expiresAt()) ? s.expiresAt() : p.expiresAt();
        var c =
                new CertificateClaim(
                        UUID.randomUUID(),
                        s.id(),
                        p.userId(),
                        s.claimGeneration(),
                        UUID.randomUUID(),
                        "RESERVED",
                        expiry);
        claims.reserve(c, capabilities.hash(capabilities.confirmationCode(c.id())), now());
        return claimResult(c);
    }

    @Transactional
    public void cancelPairing(UUID id, String pc) {
        var p = pc(id, pc, false);
        if (p.cancelledAt() != null) return;
        if (p.sessionId() != null) transactions.cancelPaired(id, pc);
        pairings.cancel(id, now());
    }

    private CertificatePairing mobile(UUID id) {
        var p = find(id, false);
        if (p.userId() == null || p.userId() != actor.getCurrentActor().userId())
            throw ApplicationException.of(CERTIFICATE_NOT_FOUND);
        return p;
    }

    private CertificatePairing pc(UUID id, String pc, boolean lock) {
        var p = find(id, lock);
        if (!capabilities.matches(pc, p.pcHash()))
            throw ApplicationException.of(CERTIFICATE_NOT_FOUND);
        return p;
    }

    private CertificatePairing find(UUID id, boolean lock) {
        return pairings.find(id, lock)
                .orElseThrow(() -> ApplicationException.of(CERTIFICATE_NOT_FOUND));
    }

    private void active(CertificatePairing p) {
        if (p.cancelledAt() != null) throw ApplicationException.of(CERTIFICATE_CONFLICT);
        if (!p.expiresAt().isAfter(now())) throw ApplicationException.of(CERTIFICATE_EXPIRED);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private CertificateClaimResult claimResult(CertificateClaim c) {
        return new CertificateClaimResult(
                c.id(), c.status(), capabilities.confirmationCode(c.id()), c.expiresAt());
    }

    private CertificatePairingResult result(CertificatePairing p, String pc, String qr) {
        CertificateSession s =
                p.sessionId() == null ? null : sessions.find(p.sessionId(), false).orElse(null);
        String status =
                p.cancelledAt() != null
                        ? "CANCELLED"
                        : !p.expiresAt().isAfter(now())
                                ? "EXPIRED"
                                : p.userId() == null
                                        ? (!p.qrExpiresAt().isAfter(now())
                                                ? "EXPIRED"
                                                : "WAITING_FOR_MOBILE")
                                        : s == null ? "PAIRED" : s.status();
        if (s != null && Set.of("BOUND", "UNKNOWN").contains(s.status())) status = s.status();
        var failure = s == null ? null : CertificateFailureStatus.from(s.failureCode());
        return new CertificatePairingResult(
                p.id(),
                status,
                p.expiresAt(),
                pc,
                qr,
                qr == null ? null : p.qrExpiresAt(),
                p.sessionId(),
                s == null ? null : s.organization(),
                s == null ? null : s.currentClaimId(),
                failure == null ? null : failure.getCode(),
                failure == null ? null : failure.getMessage());
    }
}
