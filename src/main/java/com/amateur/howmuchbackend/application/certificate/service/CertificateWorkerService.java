package com.amateur.howmuchbackend.application.certificate.service;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.RunCertificateWorkUseCase;
import com.amateur.howmuchbackend.application.port.out.certificate.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateWorkerService implements RunCertificateWorkUseCase {
    private final CertificateExecutionTransactions transactions;
    private final CertificateUploadStorePort uploads;
    private final CertificateSecretProtectionPort protection;
    private final CertificateProviderAccountPort provider;
    private final CertificateEventPublisherPort publisher;
    private final Clock clock;
    private final CertificateInstitutionCatalogPort catalog;
    private final CertificateApprovedRegistrationTransactions approvedRegistration;
    private final CertificatePairingStorePort pairings;

    public void executeOne() {
        transactions.claim().ifPresent(this::execute);
    }

    private void execute(CertificateExecution e) {
        var c = e.command();
        if (c.type().equals("CANCEL")) {
            cleanup(e);
            return;
        }
        if (!c.deadline().isAfter(LocalDateTime.now(clock))) {
            transactions.finish(e, "FAILED", "UPLOAD_EXPIRED", null, null);
            return;
        }
        CertificateMaterial material;
        try {
            material =
                    protection.openUpload(
                            c.sessionId(),
                            c.uploadRef(),
                            uploads.load(c.uploadRef(), LocalDateTime.now(clock)));
        } catch (RuntimeException failure) {
            transactions.retry(e);
            return;
        }
        try (material) {
            Optional<
                            com.amateur.howmuchbackend.application.port.in.certificate.result
                                    .CertificateInstitutionResult>
                    rule;
            try {
                rule =
                        catalog.institutions().stream()
                                .filter(
                                        i ->
                                                i.organization().equals(c.organization())
                                                        && i.supported())
                                .findFirst();
            } catch (RuntimeException unavailable) {
                transactions.retry(e);
                return;
            }
            if (rule.isEmpty()) {
                transactions.finish(e, "FAILED", "PROVIDER_UNAVAILABLE", null, null);
                return;
            }
            // This transaction commits before the first provider call. Never retry create after it.
            if (!transactions.start(e)) return;
            try {
                approvedRegistration.register(
                        e, material, rule.get().registrationAccountRequired());
            } catch (RuntimeException uncertain) {
                log.warn(
                        "Certificate registration requires reconciliation. sessionId={}"
                                + " failureType={}",
                        c.sessionId(),
                        uncertain.getClass().getSimpleName());
                transactions.finish(e, "UNKNOWN", "REGISTRATION_UNCERTAIN", null, null);
            }
        }
    }

    private void cleanup(CertificateExecution e) {
        if (pairings.findBySession(e.command().sessionId()).isPresent()) {
            transactions.discardPaired(e);
            return;
        }
        var target = transactions.cleanupTarget(e);
        if (target.state().equals("WAIT")) {
            transactions.retry(e);
            return;
        }
        if (target.state().equals("NONE")) {
            transactions.finish(e, "SUCCEEDED", null, null, null);
            return;
        }
        CertificateConnectionSecret secret;
        try {
            secret =
                    protection.openConnection(
                            e.command().sessionId(), target.connectionRef(), target.envelope());
        } catch (RuntimeException failure) {
            transactions.retry(e);
            return;
        }
        if (!transactions.startCleanup(e, target.connectionRef())) return;
        boolean deleted;
        try {
            deleted = provider.delete(e.command().organization(), secret.connectedId());
        } catch (RuntimeException uncertain) {
            deleted = false;
        }
        transactions.finishCleanup(e, deleted);
    }

    public void publishOne(boolean result) {
        transactions
                .claimPublication(result)
                .ifPresent(
                        p -> {
                            boolean acknowledged = false;
                            try {
                                publisher.publish(p);
                                acknowledged = true;
                            } catch (RuntimeException failure) {
                                /* durable retry contains no raw exception */
                            }
                            transactions.published(p, acknowledged);
                        });
    }

    public void recover() {
        transactions.recover();
    }
}
