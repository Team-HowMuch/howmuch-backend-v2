package com.amateur.howmuchbackend.application.external.service;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.certificate.service.CertificateApprovedRegistrationTransactions;
import com.amateur.howmuchbackend.application.external.model.*;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.application.port.out.external.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.codef.additional-auth",
        name = "enabled",
        havingValue = "true")
public class AdditionalAuthenticationExecutionService {
    private final AdditionalAuthenticationStorePort store;
    private final CertificateSessionStorePort sessions;
    private final CertificatePairingStorePort pairings;
    private final CertificateExecutionStorePort jobs;
    private final CertificateApprovedRegistrationTransactions registrations;
    private final Clock clock;
    private final com.amateur.howmuchbackend.application.port.out.user.LoadUserPort users;

    private final FinanceAuthenticationCompletionService completion;
    private final ExternalConnectionStorePort connections;
    private final AdditionalAuthenticationProtectorPort protector;
    private final CodefAdditionalAuthenticationPort provider;

    @Transactional
    public void completeOperation(
            AdditionalAuthenticationChallenge leased, FinanceOperationResult result) {
        var c = locked(leased.id());
        if (!current(c, leased)) return;
        if (c.cancelRequested() || !parentActive(c)) {
            terminate(c, "CANCELLED");
            return;
        }
        if (!c.expiresAt().isAfter(now())) {
            terminate(c, "UNKNOWN");
            return;
        }
        var completed = completion.complete(c.userId(), result);
        byte[] bytes = provider.encodeCompletion(completed);
        try {
            store.finishWithResult(c, protector.sealResult(c, bytes), now());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Transactional
    public void reject(AdditionalAuthenticationChallenge leased, String failureCode) {
        var c = locked(leased.id());
        if (!current(c, leased)) return;
        if (c.cancelRequested()) {
            terminate(c, "CANCELLED");
            return;
        }
        store.finish(c, "FAILED", failureCode, now());
        if (c.certificate()) jobs.finish(c.execution(), "FAILED", failureCode, null, null, now());
    }

    @Transactional
    public Optional<AdditionalAuthenticationChallenge> claim() {
        return store.claim(now());
    }

    @Transactional
    public void requestInput(
            AdditionalAuthenticationChallenge leased, AdditionalAuthenticationRequest request) {
        var c = locked(leased.id());
        if (!current(c, leased)) return;
        if (c.cancelRequested() || !parentActive(c)) {
            terminate(c, "CANCELLED");
            return;
        }
        LocalDateTime expires = c.expiresAt();
        if (request.providerExpiresAt() != null && request.providerExpiresAt().isBefore(expires))
            expires = request.providerExpiresAt();
        if (!expires.isAfter(now())) {
            terminate(c, "UNKNOWN");
            return;
        }
        UUID nextId = UUID.randomUUID();
        byte[] raw = request.payload();
        try {
            var payload =
                    protector.sealAuthentication(
                            nextId, c.userId(), c.sessionId(), c.generation() + 1, raw);
            // Accepted submissions remain immutable; a fresh generation needs explicit user input.
            store.finish(c, "FAILED", "AUTH_SMS_RETRY_REQUIRED", now());
            store.createRetry(c, nextId, request, payload, expires, now());
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    @Transactional
    public boolean start(AdditionalAuthenticationChallenge leased) {
        var c = locked(leased.id());
        if (!current(c, leased)) return false;
        if (!parentActive(c) || !c.expiresAt().isAfter(now()) || c.cancelRequested()) {
            terminate(c, c.expiresAt().isAfter(now()) ? "CANCELLED" : "EXPIRED");
            return false;
        }
        if (users.findByIdForUpdate(c.userId())
                .filter(com.amateur.howmuchbackend.domain.user.User::active)
                .isEmpty()) {
            terminate(c, "CANCELLED");
            return false;
        }
        return store.start(c, now());
    }

    @Transactional
    public void complete(
            AdditionalAuthenticationChallenge leased,
            CertificateMaterial material,
            CertificateProviderResult result) {
        var c = locked(leased.id());
        if (!current(c, leased)) return;
        if (c.cancelRequested() || !parentActive(c)) {
            terminate(c, "CANCELLED");
            return;
        }
        if (!c.expiresAt().isAfter(now())) {
            terminate(c, c.requestStarted() ? "UNKNOWN" : "EXPIRED");
            return;
        }
        registrations.completeAdditionalAuthentication(c.execution(), material, result, c.userId());
        store.finish(
                c,
                result.connectedId() == null ? "FAILED" : "SUCCEEDED",
                result.failureCode(),
                now());
    }

    @Transactional
    public void fail(AdditionalAuthenticationChallenge leased, boolean uncertain) {
        var c = locked(leased.id());
        if (!current(c, leased)) return;
        if (c.cancelRequested()) {
            terminate(c, "CANCELLED");
            return;
        }
        String code = uncertain ? "REGISTRATION_UNCERTAIN" : "PROVIDER_UNAVAILABLE";
        store.finish(c, uncertain ? "UNKNOWN" : "FAILED", code, now());
        if (c.certificate())
            jobs.finish(c.execution(), uncertain ? "UNKNOWN" : "FAILED", code, null, null, now());
    }

    @Transactional
    public void recover(UUID id) {
        var c = locked(id);
        if (!Set.of("WAITING_INPUT", "SUBMITTED", "PROCESSING").contains(c.status())) return;
        // Recheck the lease after locking: the candidate list may predate a new worker's claim.
        if (c.status().equals("PROCESSING")
                && !c.cancelRequested()
                && c.expiresAt().isAfter(now())
                && c.leaseExpiresAt().isAfter(now())) return;
        if (c.cancelRequested() || !parentActive(c)) {
            terminate(c, "CANCELLED");
            return;
        }
        if (!c.expiresAt().isAfter(now())) {
            terminate(c, c.requestStarted() ? "UNKNOWN" : "EXPIRED");
            return;
        }
        if (c.requestStarted()) {
            store.finish(c, "UNKNOWN", "REGISTRATION_UNCERTAIN", now());
            if (c.certificate())
                jobs.finish(c.execution(), "UNKNOWN", "REGISTRATION_UNCERTAIN", null, null, now());
        } else store.requeue(c, now());
    }

    private void terminate(AdditionalAuthenticationChallenge c, String status) {
        String code =
                status.equals("UNKNOWN")
                        ? "REGISTRATION_UNCERTAIN"
                        : status.equals("EXPIRED") ? "AUTH_EXPIRED" : "AUTH_CANCELLED";
        store.finish(c, status, code, now());
        if (c.certificate())
            jobs.finish(
                    c.execution(),
                    status.equals("UNKNOWN") ? "UNKNOWN" : "FAILED",
                    status.equals("UNKNOWN") ? "REGISTRATION_UNCERTAIN" : "UPLOAD_EXPIRED",
                    null,
                    null,
                    now());
    }

    private boolean current(
            AdditionalAuthenticationChallenge c, AdditionalAuthenticationChallenge leased) {
        return c.status().equals("PROCESSING")
                && c.leaseGeneration() == leased.leaseGeneration()
                && Objects.equals(c.leaseToken(), leased.leaseToken());
    }

    private AdditionalAuthenticationChallenge locked(UUID id) {
        var c = store.find(id, false).orElseThrow();
        if (c.certificate()) sessions.find(c.sessionId(), true);
        return store.find(id, true).orElseThrow();
    }

    private boolean parentActive(AdditionalAuthenticationChallenge c) {
        if (!c.certificate())
            return c.connectionId() == null
                    || connections
                            .findActiveByIdAndUserId(c.connectionId(), c.userId())
                            .isPresent();
        var s = sessions.find(c.sessionId(), false).orElse(null);
        var p = pairings.findBySession(c.sessionId()).orElse(null);
        return s != null
                && s.status().equals("REGISTERING")
                && s.expiresAt().isAfter(now())
                && p != null
                && Objects.equals(p.userId(), c.userId())
                && p.cancelledAt() == null
                && p.expiresAt().isAfter(now());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
