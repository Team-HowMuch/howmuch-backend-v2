package com.amateur.howmuchbackend.application.certificate.service;

import com.amateur.howmuchbackend.application.account.model.*;
import com.amateur.howmuchbackend.application.account.service.AccountCredentialProtectionService;
import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.application.port.out.external.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode;
import com.amateur.howmuchbackend.domain.external.*;
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
public class CertificateApprovedRegistrationTransactions {
    private final CertificateSessionStorePort sessions;
    private final CertificateConsentStorePort consents;
    private final LoadUserPort users;
    private final ExternalConnectionStorePort connections;
    private final BankConnectionPort banks;
    private final CertificateProviderAccountPort provider;
    private final AccountCredentialProtectionService credentials;
    private final CertificateSecretProtectionPort protection;
    private final CertificateExecutionStorePort jobs;
    private final Clock clock;

    /** Request-start was committed before this transaction; no provider mutation may be retried. */
    @Transactional
    public void register(
            CertificateExecution execution,
            CertificateMaterial material,
            boolean includeAccountCredentials) {
        var command = execution.command();
        var session =
                sessions.find(command.sessionId(), true)
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                CertificateStatusCode.CERTIFICATE_NOT_FOUND));
        var consent =
                consents.find(session.id())
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                CertificateStatusCode.CERTIFICATE_CONFLICT));
        var now = LocalDateTime.now(clock);
        if (!session.status().equals("REGISTERING")
                || !session.expiresAt().isAfter(now)
                || consent.connectionId() != null)
            throw ApplicationException.of(CertificateStatusCode.CERTIFICATE_CONFLICT);
        users.findByIdForUpdate(consent.userId())
                .filter(User::active)
                .orElseThrow(
                        () ->
                                ApplicationException.of(
                                        com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode
                                                .USER_NOT_FOUND));
        connections.validateStorageConfiguration();
        var connection = connections.findActiveByUserId(consent.userId()).orElse(null);
        EncryptedBankAccountCredentials encrypted =
                material.accountNumber() == null
                        ? null
                        : credentials.protect(
                                consent.userId(),
                                command.organization(),
                                new BankAccountCredentials(
                                        material.accountNumber(), material.accountPassword()));
        CertificateProviderResult result;
        if (connection == null)
            result = provider.register(command.organization(), material, includeAccountCredentials);
        else {
            boolean registered =
                    banks.registeredOrganizations(connection.connectedId())
                            .contains(command.organization());
            result =
                    registered
                            ? provider.update(
                                    connection.connectedId(),
                                    command.organization(),
                                    material,
                                    includeAccountCredentials)
                            : provider.add(
                                    connection.connectedId(),
                                    command.organization(),
                                    material,
                                    includeAccountCredentials);
        }
        now = LocalDateTime.now(clock);
        if (result.connectedId() == null) {
            jobs.finish(execution, "FAILED", result.failureCode(), null, null, now);
            return;
        }
        if (connection != null && !connection.connectedId().equals(result.connectedId()))
            throw ApplicationException.of(CertificateStatusCode.CERTIFICATE_REGISTRATION_UNCERTAIN);
        var institution =
                new ConnectedInstitution(
                        null,
                        connection == null ? null : connection.id(),
                        command.organization(),
                        ExternalConnectionStatus.CONNECTED,
                        "CF-00000",
                        encrypted == null ? null : encrypted.localCiphertext(),
                        encrypted == null ? null : encrypted.kmsCiphertext());
        if (connection == null)
            connection =
                    connections.saveNew(
                            new ExternalConnection(null, consent.userId(), result.connectedId()),
                            institution);
        else if (!connections.hasInstitution(connection.id(), command.organization()))
            connections.saveInstitution(institution);
        else if (encrypted != null)
            connections.updateInstitutionCredentials(
                    connection.id(),
                    command.organization(),
                    encrypted.localCiphertext(),
                    encrypted.kmsCiphertext());
        UUID ref = UUID.randomUUID();
        var envelope =
                protection.sealConnection(
                        session.id(),
                        ref,
                        new CertificateConnectionSecret(
                                result.connectedId(),
                                material.accountNumber(),
                                material.accountPassword(),
                                material.birthDate()));
        // The ownership FK, job result, user connection, consent and outbox commit together.
        jobs.finish(execution, "SUCCEEDED", null, ref, envelope, now);
        consents.bind(consent, ref, connection.id(), now);
    }
}
