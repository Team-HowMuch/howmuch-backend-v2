package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.model.*;
import com.amateur.howmuchbackend.application.port.in.account.RunCertificateReferenceWorkUseCase;
import com.amateur.howmuchbackend.application.port.out.account.*;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateInstitutionCatalogPort;
import com.amateur.howmuchbackend.application.port.out.external.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.CertificateReferenceStatus;
import com.amateur.howmuchbackend.domain.external.BankAuthenticationMethod;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.certificate-reference",
        name = "enabled",
        havingValue = "true")
public class CertificateReferenceWorkerService implements RunCertificateReferenceWorkUseCase {
    private final CertificateReferenceOperationStorePort operations;
    private final CertificateReferenceProtectionPort protection;
    private final CertificateReferencePublisherPort publisher;
    private final BankCertificateReferencePort provider;
    private final BankConnectionPort accounts;
    private final CertificateInstitutionCatalogPort catalog;
    private final CertificateReferenceTransactions transactions;
    private final Clock clock;

    public void executeOne() {
        var e = operations.claim(now()).orElse(null);
        if (e == null) return;
        boolean uncertain = e.operation().providerStartedAt() != null;
        boolean completing = false;
        try {
            var op = e.operation();
            if (!uncertain && !op.hasExecutionWindow(now())) {
                transactions.finish(e, CertificateReferenceStatus.EXPIRED, "CERTIFICATE_EXPIRED");
                return;
            }
            var connection = transactions.connectionForRead(e);
            var registered = provider.registeredAccounts(connection.connectedId());
            var target =
                    registered.stream()
                            .filter(a -> a.organization().equals(op.organization()))
                            .findFirst();
            var rule =
                    catalog.institutions().stream()
                            .filter(
                                    i ->
                                            i.supported()
                                                    && i.organization().equals(op.organization()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            ApplicationException.of(
                                                    ExternalConnectionStatusCode
                                                            .BANK_NOT_SUPPORTED));
            CertificateReferenceSecret secret =
                    e.envelope() == null ? null : protection.openReference(op, e.envelope());
            if (target.isPresent()) {
                if (uncertain && !target.get().certificate()) {
                    transactions.defer(e);
                    return;
                }
                if (rule.requiresAccountCredentials()
                        && (secret == null || secret.accountNumber() == null)) {
                    if (uncertain) transactions.defer(e);
                    else
                        transactions.finish(
                                e,
                                CertificateReferenceStatus.FAILED,
                                "BANK_ACCOUNT_RECONNECT_REQUIRED");
                    return;
                }
                // A reused target must verify newly supplied query credentials before they replace
                // stored credentials.
                if (secret != null && secret.accountNumber() != null)
                    accounts.findAccounts(
                            connection.connectedId(),
                            op.organization(),
                            secret.birthDate(),
                            new BankAccountCredentials(
                                    secret.accountNumber(), secret.accountPassword()));
                completing = true;
                transactions.connected(e, secret, !uncertain, target.get().authenticationMethod());
                return;
            }
            if (uncertain) {
                transactions.defer(e);
                return;
            }
            if (registered.stream()
                    .noneMatch(
                            a ->
                                    a.organization().equals(op.sourceOrganization())
                                            && a.certificate()))
                throw ApplicationException.of(
                        ExternalConnectionStatusCode.BANK_CERTIFICATE_REFERENCE_INVALID);
            if (secret == null || !secret.connectedId().equals(connection.connectedId()))
                throw ApplicationException.of(
                        ExternalConnectionStatusCode.BANK_CERTIFICATE_REFERENCE_INVALID);
            // The source lookup can consume most of the admission deadline. Recheck before
            // mutation.
            if (!op.hasExecutionWindow(now())) {
                transactions.finish(e, CertificateReferenceStatus.EXPIRED, "CERTIFICATE_EXPIRED");
                return;
            }
            if (!transactions.start(e)) {
                transactions.finish(e, CertificateReferenceStatus.EXPIRED, "CERTIFICATE_EXPIRED");
                return;
            }
            uncertain = true;
            provider.addReference(
                    connection.connectedId(), op.sourceOrganization(), op.organization(), secret);
            completing = true;
            transactions.connected(e, secret, false, BankAuthenticationMethod.CERTIFICATE);
        } catch (CertificateReferenceUncertainException unknown) {
            transactions.finish(
                    e, CertificateReferenceStatus.UNKNOWN, "CERTIFICATE_REFERENCE_UNCERTAIN");
        } catch (ApplicationException failure) {
            String code = failure.getStatusCode().getCode();
            if (completing) transactions.defer(e);
            else if (e.operation().status() == CertificateReferenceStatus.UNKNOWN)
                transactions.defer(e);
            else if (uncertain
                    && failure.getStatusCode()
                            == ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED)
                transactions.finish(e, CertificateReferenceStatus.ACTION_REQUIRED, code);
            else if (uncertain
                    && (failure.getStatusCode()
                                    == ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE
                            || failure.getStatusCode()
                                    == ExternalConnectionStatusCode.CODEF_RESPONSE_INVALID
                            || failure.getStatusCode()
                                    == ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND))
                transactions.finish(
                        e, CertificateReferenceStatus.UNKNOWN, "CERTIFICATE_REFERENCE_UNCERTAIN");
            else if (!uncertain
                    && (failure.getStatusCode()
                                    == ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE
                            || failure.getStatusCode()
                                    == ExternalConnectionStatusCode.CODEF_RESPONSE_INVALID))
                transactions.defer(e);
            else transactions.finish(e, CertificateReferenceStatus.FAILED, code);
        } catch (RuntimeException localFailure) {
            // This also handles DB/KMS failure after provider success. Never repeat a started
            // mutation.
            transactions.defer(e);
        }
    }

    public void publishOne() {
        var p = operations.claimPublication(now()).orElse(null);
        if (p == null) return;
        try {
            publisher.publish(p);
            operations.published(p, now());
        } catch (RuntimeException unavailable) {
            operations.publicationFailed(p, now());
        }
    }

    public void recover() {
        transactions.recover();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
