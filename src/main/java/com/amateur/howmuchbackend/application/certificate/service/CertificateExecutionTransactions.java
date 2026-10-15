package com.amateur.howmuchbackend.application.certificate.service;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.ReceiveCertificateMessagesUseCase;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateResultCommand;
import com.amateur.howmuchbackend.application.port.out.certificate.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateExecutionTransactions implements ReceiveCertificateMessagesUseCase {
    private final CertificateExecutionStorePort jobs;
    private final CertificateRelayStorePort relay;
    private final CertificateResultInboxPort results;
    private final Clock clock;
    private final CertificateSessionStorePort sessions;
    private final CertificateConsentStorePort consents;
    private final CertificatePairingStorePort pairings;
    private final com.amateur.howmuchbackend.application.certificate.policy
                    .CertificateRegistrationPolicy
            policy;

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    @Transactional
    public boolean receiveCommand(ReceiveCertificateCommand c, String hash) {
        return jobs.receive(c, hash, now());
    }

    @Transactional
    public boolean receiveResult(ReceiveCertificateResultCommand e, String hash) {
        var time = now();
        var s = sessions.find(e.sessionId(), true).orElse(null);
        return results.apply(e, hash, policy.mayApply(s, e, time), time);
    }

    @Transactional
    public Optional<CertificateExecution> claim() {
        return jobs.claim(now());
    }

    @Transactional
    public boolean start(CertificateExecution e) {
        var time = now();
        var s = sessions.find(e.command().sessionId(), true).orElse(null);
        if (!policy.mayStart(s, e.command(), time)
                || (consents.find(e.command().sessionId()).isEmpty()
                        && pairings.findBySession(e.command().sessionId())
                                .filter(
                                        p ->
                                                p.userId() != null
                                                        && p.cancelledAt() == null
                                                        && p.expiresAt().isAfter(time))
                                .isEmpty())) {
            jobs.finish(e, "FAILED", "UPLOAD_EXPIRED", null, null, time);
            return false;
        }
        return jobs.startRequest(e, time);
    }

    @Transactional
    public void retry(CertificateExecution e) {
        jobs.retry(e, now());
    }

    @Transactional
    public void finish(
            CertificateExecution e,
            String status,
            String code,
            UUID ref,
            CertificateEnvelope secret) {
        jobs.finish(e, status, code, ref, secret, now());
    }

    @Transactional
    public Optional<CertificatePublication> claimPublication(boolean result) {
        return relay.claimPublication(result, now());
    }

    @Transactional
    public void published(CertificatePublication p, boolean acknowledged) {
        relay.completePublication(p, acknowledged, now());
    }

    @Transactional
    public CertificateCleanupTarget cleanupTarget(CertificateExecution e) {
        var s = sessions.find(e.command().sessionId(), true).orElse(null);
        return consents.find(e.command().sessionId()).isEmpty() && policy.mayCleanup(s)
                ? jobs.cleanupTarget(e, now())
                : CertificateCleanupTarget.none();
    }

    @Transactional
    public boolean startCleanup(CertificateExecution e, UUID ref) {
        return jobs.startCleanup(e, ref, now());
    }

    @Transactional
    public void finishCleanup(CertificateExecution e, boolean deleted) {
        jobs.finishCleanup(e, deleted, now());
    }

    @Transactional
    public void discardPaired(CertificateExecution e) {
        var s = sessions.find(e.command().sessionId(), true).orElse(null);
        if (consents.find(e.command().sessionId()).isEmpty() && policy.mayCleanup(s))
            jobs.discardConnection(s.id(), now());
        jobs.finish(e, "SUCCEEDED", null, null, null, now());
    }

    @Transactional
    public void recover() {
        jobs.recover(now());
        relay.recoverPublications(now());
    }
}
