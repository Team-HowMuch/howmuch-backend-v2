package com.amateur.howmuchbackend.application.certificate.service;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.*;

import com.amateur.howmuchbackend.application.certificate.model.CertificateMaterial;
import com.amateur.howmuchbackend.application.certificate.policy.CertificateRegistrationPolicy;
import com.amateur.howmuchbackend.application.port.in.certificate.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.*;
import com.amateur.howmuchbackend.application.port.in.certificate.result.*;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.StatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateRegistrationService
        implements CreateCertificateRegistrationUseCase,
                SubmitCertificateUploadUseCase,
                GetCertificateRegistrationUseCase,
                CancelCertificateRegistrationUseCase {
    private final CertificateRegistrationTransactions transactions;
    private final CertificateSecretProtectionPort protection;
    private final CertificateCapabilityPort capabilities;
    private final CertificateInstitutionCatalogPort catalog;
    private final CertificateRequestLimitPort limits;
    private final CertificateUploadStorePort uploads;
    private final CertificateRegistrationPolicy policy;
    private final Clock clock;

    public CertificateRegistrationResult create(CreateCertificateRegistrationCommand c) {
        if (!limits.acquire(capabilities.hash(c.remoteAddress())))
            throw ApplicationException.of(CERTIFICATE_RATE_LIMITED);
        institution(c.organization());
        var id = UUID.randomUUID();
        var upload = UUID.randomUUID();
        String pc = capabilities.mint(), token = capabilities.mint();
        var now = LocalDateTime.now(clock);
        var expires = now.plusMinutes(10);
        transactions.create(
                id,
                c.organization(),
                capabilities.hash(pc),
                upload,
                capabilities.hash(token),
                now,
                expires);
        return new CertificateRegistrationResult(
                id, c.organization(), "CREATED", expires, pc, token, null, null);
    }

    public CertificateRegistrationResult submit(SubmitCertificateUploadCommand c) {
        policy.material(c);
        var s = transactions.get(c.sessionId(), c.pcCapability());
        var now = LocalDateTime.now(clock);
        policy.active(s, now);
        var rule = institution(s.organization());
        if (rule.requiresAccountCredentials()
                        && (c.accountNumber() == null
                                || c.accountNumber().isBlank()
                                || c.accountPassword() == null
                                || c.accountPassword().isBlank())
                || rule.birthDateRequired() && (c.birthDate() == null || c.birthDate().isBlank()))
            throw ApplicationException.of(CERTIFICATE_INPUT_INVALID);
        if (c.uploadCapability() == null
                || !c.uploadCapability().matches("[A-Za-z0-9_-]{43}")
                || !uploads.accepts(s.uploadRef(), capabilities.hash(c.uploadCapability()), now))
            throw ApplicationException.of(CERTIFICATE_CONFLICT);
        try (var material =
                new CertificateMaterial(
                        c.der(),
                        c.key(),
                        c.password(),
                        emptyToNull(c.accountNumber()),
                        emptyToNull(c.accountPassword()),
                        emptyToNull(c.birthDate()))) {
            var envelope = protection.sealUpload(s.id(), s.uploadRef(), material);
            transactions.submit(
                    s.id(), c.pcCapability(), capabilities.hash(c.uploadCapability()), envelope);
        }
        return get(s.id(), c.pcCapability());
    }

    public CertificateRegistrationResult get(UUID id, String pc) {
        var s = transactions.get(id, pc);
        StatusCode failure =
                com.amateur.howmuchbackend.application.certificate.model.CertificateFailureStatus
                        .from(s.failureCode());
        return new CertificateRegistrationResult(
                s.id(),
                s.organization(),
                s.status(),
                s.expiresAt(),
                null,
                null,
                failure == null ? null : failure.getCode(),
                failure == null ? null : failure.getMessage());
    }

    public void cancel(UUID id, String pc) {
        transactions.cancel(id, pc);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private CertificateInstitutionResult institution(String code) {
        return catalog.institutions().stream()
                .filter(i -> i.organization().equals(code) && i.supported())
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(CERTIFICATE_BANK_UNAVAILABLE));
    }
}
