package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.CertificateReferenceSecret;
import com.amateur.howmuchbackend.application.account.policy.CertificateReferenceInputPolicy;
import com.amateur.howmuchbackend.application.port.in.account.*;
import com.amateur.howmuchbackend.application.port.in.account.command.StartCertificateReferenceConnectionCommand;
import com.amateur.howmuchbackend.application.port.in.account.result.*;
import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateInstitutionResult;
import com.amateur.howmuchbackend.application.port.out.account.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateInstitutionCatalogPort;
import com.amateur.howmuchbackend.application.port.out.external.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.*;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.user.User;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.certificate-reference",
        name = "enabled",
        havingValue = "true")
public class CertificateReferenceService
        implements GetCertificateReferencesUseCase,
                StartCertificateReferenceConnectionUseCase,
                GetCertificateReferenceOperationUseCase,
                ReceiveCertificateReferenceUseCase {
    private final CurrentActorPort actor;
    private final LoadUserPort users;
    private final ExternalConnectionStorePort connections;
    private final CertificateReferenceOperationStorePort operations;
    private final CertificateReferenceProtectionPort protection;
    private final InstitutionAuthenticationStorePort authentication;
    private final CertificateInstitutionCatalogPort catalog;
    private final BankCatalog banks;
    private final CertificateReferenceInputPolicy inputs;
    private final FinancialConnectionMutationGuard guard;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CertificateReferencesResult getCertificateReferences(String organization) {
        var rule = rule(organization);
        long user = actor.getCurrentActor().userId();
        users.findById(user)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        var active = operations.findActive(user).map(this::result).orElse(null);
        var connection = connections.findActiveByUserId(user).orElse(null);
        if (connection == null)
            return new CertificateReferencesResult(
                    organization,
                    rule.authenticationLevel(),
                    rule.birthDateRequired(),
                    null,
                    false,
                    List.of(),
                    active);
        var registered = authentication.findCertificateOrganizations(connection.id());
        var bankMap =
                banks.getAllBanks().stream()
                        .collect(
                                Collectors.toMap(
                                        BankCatalogResult::organization, Function.identity()));
        var references =
                registered.stream()
                        .distinct()
                        .sorted()
                        .filter(
                                source ->
                                        !source.equals(organization) && bankMap.containsKey(source))
                        .map(
                                source ->
                                        new CertificateReferencesResult.Reference(
                                                source,
                                                bankMap.get(source).name(),
                                                bankMap.get(source).iconUrl()))
                        .toList();
        boolean existing = connections.hasInstitution(connection.id(), organization);
        return new CertificateReferencesResult(
                organization,
                rule.authenticationLevel(),
                rule.birthDateRequired(),
                connection.id(),
                existing,
                references,
                active);
    }

    @Transactional
    public CertificateReferenceOperationResult startCertificateReference(
            StartCertificateReferenceConnectionCommand command) {
        long user = actor.getCurrentActor().userId();
        users.findByIdForUpdate(user)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        var c = inputs.normalize(command, LocalDate.now(clock));
        String hash = protection.requestHash(user, c);
        var previous = operations.findByKey(user, c.idempotencyKey()).orElse(null);
        if (previous != null) {
            if (!previous.requestHash().equals(hash))
                throw ApplicationException.of(
                        ExternalConnectionStatusCode.CERTIFICATE_REFERENCE_IDEMPOTENCY_CONFLICT);
            return result(previous);
        }
        inputs.validateRequirements(c, rule(c.organization()));
        connections.validateStorageConfiguration();
        var connection =
                connections
                        .findActiveByIdAndUserId(c.connectionId(), user)
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                ExternalConnectionStatusCode
                                                        .EXTERNAL_CONNECTION_NOT_FOUND));
        rule(c.sourceOrganization());
        guard.requireAvailable(user);
        var now = now();
        var op =
                new CertificateReferenceOperation(
                        UUID.randomUUID(),
                        user,
                        connection.id(),
                        c.sourceOrganization(),
                        c.organization(),
                        c.idempotencyKey(),
                        hash,
                        protection.connectionHash(user, connection.connectedId()),
                        CertificateReferenceStatus.QUEUED,
                        false,
                        null,
                        now,
                        now.plusMinutes(10),
                        null,
                        null);
        var secret =
                new CertificateReferenceSecret(
                        connection.connectedId(),
                        c.birthDate(),
                        c.accountNumber(),
                        c.accountPassword());
        if (!operations.create(op, protection.sealReference(op, secret)))
            throw ApplicationException.of(ExternalConnectionStatusCode.CERTIFICATE_REFERENCE_BUSY);
        return result(op);
    }

    @Transactional(readOnly = true)
    public CertificateReferenceOperationResult getCertificateReferenceOperation(UUID id) {
        long user = actor.getCurrentActor().userId();
        users.findById(user)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        return result(
                operations
                        .find(id, user)
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                ExternalConnectionStatusCode
                                                        .CERTIFICATE_REFERENCE_NOT_FOUND)));
    }

    @Transactional
    public boolean receiveReference(UUID message, UUID operation, String hash) {
        return operations.receive(message, operation, hash, now());
    }

    private CertificateInstitutionResult rule(String organization) {
        return catalog.institutions().stream()
                .filter(i -> i.supported() && i.organization().equals(organization))
                .findFirst()
                .orElseThrow(
                        () ->
                                ApplicationException.of(
                                        ExternalConnectionStatusCode.BANK_NOT_SUPPORTED));
    }

    private CertificateReferenceOperationResult result(CertificateReferenceOperation op) {
        String code = op.failureCode(), message = null;
        if (code != null) {
            if ("CERTIFICATE_EXPIRED".equals(code)) message = "연결 요청 시간이 만료되었습니다. 다시 요청해주세요.";
            else
                try {
                    message = ExternalConnectionStatusCode.valueOf(code).getMessage();
                } catch (IllegalArgumentException ignored) {
                    message = ExternalConnectionStatusCode.CODEF_UNMAPPED_ERROR.getMessage();
                }
        }
        return new CertificateReferenceOperationResult(
                op.id(),
                op.connectionId(),
                op.sourceOrganization(),
                op.organization(),
                op.status(),
                op.alreadyConnected(),
                code,
                message,
                op.expiresAt(),
                op.status().terminal() ? 0 : 2000);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
