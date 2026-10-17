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
    private final CertificatePairingStorePort pairings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService
            additionalAuthentication;

    /** Request-start was committed before this transaction; no provider mutation may be retried. */
    @Transactional
    public void register(
            CertificateExecution execution,
            CertificateMaterial material,
            boolean includeAccountCredentials) {
        var command = execution.command();
        if (additionalAuthentication != null) {
            var initialPairing = pairings.findBySession(command.sessionId());
            if (initialPairing.isPresent()) pairings.find(initialPairing.get().id(), true);
        }
        var session =
                sessions.find(command.sessionId(), true)
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                CertificateStatusCode.CERTIFICATE_NOT_FOUND));
        var pairing = pairings.findBySession(session.id()).orElse(null);
        var consent = consents.find(session.id()).orElse(null);
        var now = LocalDateTime.now(clock);
        if (!session.status().equals("REGISTERING")
                || !session.expiresAt().isAfter(now)
                || (pairing == null && (consent == null || consent.connectionId() != null))
                || (pairing != null
                        && (pairing.userId() == null
                                || pairing.cancelledAt() != null
                                || !pairing.expiresAt().isAfter(now))))
            throw ApplicationException.of(CertificateStatusCode.CERTIFICATE_CONFLICT);
        long user = pairing == null ? consent.userId() : pairing.userId();
        users.findByIdForUpdate(user)
                .filter(User::active)
                .orElseThrow(
                        () ->
                                ApplicationException.of(
                                        com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode
                                                .USER_NOT_FOUND));
        if (pairing != null && pairings.hasUnresolvedAuthentication(user, session.id())) {
            // REGISTER failures use the existing contract-conflict event code.
            jobs.finish(execution, "FAILED", "CONTRACT_CONFLICT", null, null, now);
            return;
        }
        if (additionalAuthentication != null
                && !additionalAuthentication.tryAdmitInitialOperation(user, command.organization())) {
            jobs.finish(execution, "FAILED", "CONTRACT_CONFLICT", null, null, now);
            return;
        }
        connections.validateStorageConfiguration();
        var connection = connections.findActiveByUserId(user).orElse(null);
        if (pairing != null)
            pairings.prepare(session.id(), connection == null ? null : connection.id());
        CertificateProviderResult result;
        try {
            if (connection == null)
                result =
                        provider.register(
                                command.organization(), material, includeAccountCredentials);
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
        } catch (
                com.amateur.howmuchbackend.application.external.model
                                .AdditionalAuthenticationRequiredException
                        required) {
            if (additionalAuthentication != null
                    && additionalAuthentication.suspend(execution, required.request())) return;
            jobs.finish(
                    execution,
                    "FAILED",
                    "ADDITIONAL_AUTH_REQUIRED",
                    null,
                    null,
                    LocalDateTime.now(clock));
            return;
        }
        finishRegistration(execution, material, result, user, connection, consent, pairing == null);
    }

    @Transactional
    public void completeAdditionalAuthentication(
            CertificateExecution execution,
            CertificateMaterial material,
            CertificateProviderResult result,
            long user) {
        var session = sessions.find(execution.command().sessionId(), true).orElseThrow();
        var p = pairings.findBySession(session.id()).orElseThrow();
        var now = LocalDateTime.now(clock);
        if (!session.status().equals("REGISTERING")
                || !session.expiresAt().isAfter(now)
                || p.userId() == null
                || p.userId() != user
                || p.cancelledAt() != null
                || !p.expiresAt().isAfter(now))
            throw ApplicationException.of(CertificateStatusCode.CERTIFICATE_CONFLICT);
        users.findByIdForUpdate(user).filter(User::active).orElseThrow();
        var connection = connections.findActiveByUserId(user).orElse(null);
        if (!Objects.equals(p.existingConnectionId(), connection == null ? null : connection.id()))
            throw ApplicationException.of(CertificateStatusCode.CERTIFICATE_CONFLICT);
        finishRegistration(execution, material, result, user, connection, null, false);
    }

    private void finishRegistration(
            CertificateExecution execution,
            CertificateMaterial material,
            CertificateProviderResult result,
            long user,
            ExternalConnection connection,
            CertificateConsent consent,
            boolean bindImmediately) {
        var command = execution.command();
        var session = sessions.find(command.sessionId(), true).orElseThrow();
        var now = LocalDateTime.now(clock);
        if (result.connectedId() == null) {
            jobs.finish(execution, "FAILED", result.failureCode(), null, null, now);
            return;
        }
        if (connection != null && !connection.connectedId().equals(result.connectedId()))
            throw ApplicationException.of(CertificateStatusCode.CERTIFICATE_REGISTRATION_UNCERTAIN);
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
        if (bindImmediately) {
            connection =
                    persist(
                            user,
                            command.organization(),
                            result.connectedId(),
                            material.accountNumber(),
                            material.accountPassword(),
                            connection);
            consents.bind(consent, ref, connection.id(), now);
        }
    }

    /** Financial authentication is already complete. This transaction performs no provider call. */
    @Transactional
    public void bindVerified(
            CertificateConsent consent,
            com.amateur.howmuchbackend.domain.certificate.CertificateSession session) {
        var p =
                pairings.findBySession(session.id())
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                CertificateStatusCode.CERTIFICATE_CONFLICT));
        var now = LocalDateTime.now(clock);
        if (!p.authenticationPrepared()
                || p.userId() != consent.userId()
                || p.cancelledAt() != null
                || !p.expiresAt().isAfter(now)
                || session.connectionRef() == null)
            throw ApplicationException.of(CertificateStatusCode.CERTIFICATE_CONFLICT);
        var envelope =
                jobs.readyConnection(session.id(), session.connectionRef())
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                CertificateStatusCode.CERTIFICATE_CONFLICT));
        var secret = protection.openConnection(session.id(), session.connectionRef(), envelope);
        var connection = connections.findActiveByUserId(consent.userId()).orElse(null);
        if (!Objects.equals(p.existingConnectionId(), connection == null ? null : connection.id())
                || (connection != null && !connection.connectedId().equals(secret.connectedId())))
            throw ApplicationException.of(CertificateStatusCode.CERTIFICATE_CONFLICT);
        connection =
                persist(
                        consent.userId(),
                        session.organization(),
                        secret.connectedId(),
                        secret.accountNumber(),
                        secret.accountPassword(),
                        connection);
        consents.bind(consent, session.connectionRef(), connection.id(), now);
    }

    private ExternalConnection persist(
            long user,
            String organization,
            String connectedId,
            String accountNumber,
            String accountPassword,
            ExternalConnection connection) {
        EncryptedBankAccountCredentials encrypted =
                accountNumber == null
                        ? null
                        : credentials.protect(
                                user,
                                organization,
                                new BankAccountCredentials(accountNumber, accountPassword));
        var institution =
                new ConnectedInstitution(
                        null,
                        connection == null ? null : connection.id(),
                        organization,
                        ExternalConnectionStatus.CONNECTED,
                        "CF-00000",
                        encrypted == null ? null : encrypted.localCiphertext(),
                        encrypted == null ? null : encrypted.kmsCiphertext());
        if (connection == null)
            return connections.saveNew(
                    new ExternalConnection(null, user, connectedId), institution);
        if (!connections.hasInstitution(connection.id(), organization))
            connections.saveInstitution(institution);
        else if (encrypted != null)
            connections.updateInstitutionCredentials(
                    connection.id(),
                    organization,
                    encrypted.localCiphertext(),
                    encrypted.kmsCiphertext());
        return connection;
    }
}
