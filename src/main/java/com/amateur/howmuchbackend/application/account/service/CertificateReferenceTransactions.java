package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.model.*;
import com.amateur.howmuchbackend.application.port.out.account.*;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.external.InstitutionAuthenticationStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.CertificateReferenceStatus;
import com.amateur.howmuchbackend.domain.external.*;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.user.User;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.certificate-reference",
        name = "enabled",
        havingValue = "true")
public class CertificateReferenceTransactions {
    private final CertificateReferenceOperationStorePort operations;
    private final ExternalConnectionStorePort connections;
    private final LoadUserPort users;
    private final FinancialConnectionMutationStatePort mutationState;
    private final CertificateReferenceProtectionPort protection;
    private final AccountCredentialProtectionService credentials;
    private final Clock clock;
    private final InstitutionAuthenticationStorePort authentication;

    @Transactional
    public boolean start(CertificateReferenceExecution e) {
        var connection = ownedConnection(e);
        if (mutationState.hasUnresolvedCertificate(e.operation().userId()))
            throw ApplicationException.of(
                    ExternalConnectionStatusCode.CERTIFICATE_REFERENCE_IN_PROGRESS);
        return operations.markProviderStarted(e, now());
    }

    @Transactional
    public void connected(
            CertificateReferenceExecution e,
            CertificateReferenceSecret secret,
            boolean existing,
            BankAuthenticationMethod method) {
        var connection = ownedConnection(e);
        EncryptedBankAccountCredentials encrypted =
                secret == null || secret.accountNumber() == null
                        ? null
                        : credentials.protect(
                                e.operation().userId(),
                                e.operation().organization(),
                                new BankAccountCredentials(
                                        secret.accountNumber(), secret.accountPassword()));
        if (!operations.finish(e, CertificateReferenceStatus.CONNECTED, null, existing, now()))
            return;
        if (!connections.hasInstitution(connection.id(), e.operation().organization())) {
            connections.saveInstitution(
                    new ConnectedInstitution(
                            null,
                            connection.id(),
                            e.operation().organization(),
                            ExternalConnectionStatus.CONNECTED,
                            "CF-00000",
                            encrypted == null ? null : encrypted.localCiphertext(),
                            encrypted == null ? null : encrypted.kmsCiphertext()));
        } else if (encrypted != null) {
            connections.updateInstitutionCredentials(
                    connection.id(),
                    e.operation().organization(),
                    encrypted.localCiphertext(),
                    encrypted.kmsCiphertext());
        }
        authentication.recordMethod(connection.id(), e.operation().organization(), method);
    }

    @Transactional
    public void finish(
            CertificateReferenceExecution e, CertificateReferenceStatus status, String code) {
        operations.finish(e, status, code, false, now());
    }

    @Transactional
    public void defer(CertificateReferenceExecution e) {
        operations.defer(e, now());
    }

    @Transactional
    public void recover() {
        operations.recover(now());
    }

    public ExternalConnection connectionForRead(CertificateReferenceExecution e) {
        var op = e.operation();
        var connection =
                connections
                        .findActiveByIdAndUserId(op.connectionId(), op.userId())
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                ExternalConnectionStatusCode
                                                        .EXTERNAL_CONNECTION_NOT_FOUND));
        if (!protection
                .connectionHash(op.userId(), connection.connectedId())
                .equals(op.connectionHash()))
            throw ApplicationException.of(
                    ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND);
        return connection;
    }

    private ExternalConnection ownedConnection(CertificateReferenceExecution e) {
        users.findByIdForUpdate(e.operation().userId())
                .filter(User::active)
                .orElseThrow(
                        () ->
                                ApplicationException.of(
                                        ExternalConnectionStatusCode
                                                .EXTERNAL_CONNECTION_NOT_FOUND));
        return connectionForRead(e);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
