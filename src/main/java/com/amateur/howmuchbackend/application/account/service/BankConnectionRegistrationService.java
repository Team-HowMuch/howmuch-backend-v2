package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.model.EncryptedBankAccountCredentials;
import com.amateur.howmuchbackend.application.port.in.account.command.ConnectBankCommand;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort.BankRegistration;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.external.ConnectedInstitution;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.user.User;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class BankConnectionRegistrationService {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final SaveAccountPort saveAccountPort;
    private final BankConnectionPort bankConnectionPort;
    private final BankCatalog bankCatalog;
    private final AccountCredentialProtectionService credentialProtection;

    private com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService
            authentication;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void authentication(
            com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService
                    value) {
        authentication = value;
    }

    @Transactional(
            noRollbackFor =
                    com.amateur.howmuchbackend.application.external.model
                            .AdditionalAuthenticationPendingException.class)
    public RegisteredBank register(ConnectBankCommand command) {
        bankCatalog.validate(command.organization());
        Long userId = currentActorPort.getCurrentActor().userId();
        loadUserPort
                .findByIdForUpdate(userId)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        if (authentication != null) authentication.assertNoPending(userId, command.organization());
        connectionStorePort.validateStorageConfiguration();
        String birthDate = command.birthDate();
        BankAccountCredentials credentials = credentials(command);
        EncryptedBankAccountCredentials encrypted =
                credentials == null
                        ? null
                        : credentialProtection.protect(userId, command.organization(), credentials);
        BankRegistration registration =
                new BankRegistration(
                        command.organization(),
                        command.loginId().trim(),
                        command.password(),
                        birthDate,
                        credentials);

        ExternalConnection connection = connectionStorePort.findActiveByUserId(userId).orElse(null);
        Long expectedConnection = connection == null ? null : connection.id();
        boolean replaceConnection = false;
        try {
            if (connection == null) {
                var result = bankConnectionPort.create(registration);
                connection =
                        connectionStorePort.saveNew(
                                new ExternalConnection(null, userId, result.connectedId()),
                                institution(
                                        null,
                                        command.organization(),
                                        result.resultCode(),
                                        encrypted));
            } else {
                Set<String> registered =
                        bankConnectionPort.registeredOrganizations(connection.connectedId());
                if (registered.isEmpty()) {
                    replaceConnection = true;
                    var result = bankConnectionPort.create(registration);
                    saveAccountPort.deactivateByConnectionId(connection.id());
                    connection =
                            connectionStorePort.saveNew(
                                    new ExternalConnection(null, userId, result.connectedId()),
                                    institution(
                                            null,
                                            command.organization(),
                                            result.resultCode(),
                                            encrypted));
                } else {
                    boolean locallyRegistered =
                            connectionStorePort.hasInstitution(
                                    connection.id(), command.organization());
                    var result =
                            registered.contains(command.organization())
                                    ? bankConnectionPort.update(
                                            connection.connectedId(), registration)
                                    : bankConnectionPort.add(
                                            connection.connectedId(), registration);
                    if (!locallyRegistered) {
                        connectionStorePort.saveInstitution(
                                institution(
                                        connection.id(),
                                        command.organization(),
                                        result.resultCode(),
                                        encrypted));
                    } else if (encrypted != null) {
                        connectionStorePort.updateInstitutionCredentials(
                                connection.id(),
                                command.organization(),
                                encrypted.localCiphertext(),
                                encrypted.kmsCiphertext());
                    }
                }
            }
        } catch (
                com.amateur.howmuchbackend.application.external.model
                                .AdditionalAuthenticationRequiredException
                        required) {
            if (authentication == null)
                throw ApplicationException.of(
                        ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED);
            var operation =
                    new com.amateur.howmuchbackend.application.external.model.FinanceOperation(
                            "BANK_CONNECT",
                            command.organization(),
                            expectedConnection,
                            null,
                            null,
                            command,
                            replaceConnection);
            throw new com.amateur.howmuchbackend.application.external.model
                    .AdditionalAuthenticationPendingException(
                    authentication.suspendOperation(userId, operation, required.request()));
        }
        return new RegisteredBank(connection, birthDate, credentials);
    }

    @Transactional
    public Long completeAdditionalAuthentication(
            long userId,
            com.amateur.howmuchbackend.application.external.model.FinanceOperation operation,
            BankConnectionPort.ConnectionResult result) {
        loadUserPort
                .findByIdForUpdate(userId)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        ExternalConnection current = connectionStorePort.findActiveByUserId(userId).orElse(null);
        if (!java.util.Objects.equals(
                current == null ? null : current.id(), operation.connectionId()))
            throw ApplicationException.of(
                    ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND);
        BankAccountCredentials credentials = credentials(operation.registration());
        var encrypted =
                credentials == null
                        ? null
                        : credentialProtection.protect(
                                userId, operation.organization(), credentials);
        if (current == null || operation.replaceConnection()) {
            if (current != null) saveAccountPort.deactivateByConnectionId(current.id());
            current =
                    connectionStorePort.saveNew(
                            new ExternalConnection(null, userId, result.connectedId()),
                            institution(
                                    null,
                                    operation.organization(),
                                    result.resultCode(),
                                    encrypted));
        } else {
            if (!current.connectedId().equals(result.connectedId()))
                throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_RESPONSE_INVALID);
            if (!connectionStorePort.hasInstitution(current.id(), operation.organization()))
                connectionStorePort.saveInstitution(
                        institution(
                                current.id(),
                                operation.organization(),
                                result.resultCode(),
                                encrypted));
            else if (encrypted != null)
                connectionStorePort.updateInstitutionCredentials(
                        current.id(),
                        operation.organization(),
                        encrypted.localCiphertext(),
                        encrypted.kmsCiphertext());
        }
        return current.id();
    }

    private BankAccountCredentials credentials(ConnectBankCommand command) {
        if (!bankCatalog.requiresAccountCredentials(command.organization())) {
            return null;
        }
        if (!StringUtils.hasText(command.accountNumber())
                || !StringUtils.hasText(command.accountPassword())) {
            throw ApplicationException.of(
                    ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_REQUIRED);
        }
        return new BankAccountCredentials(
                command.accountNumber().trim(), command.accountPassword());
    }

    private ConnectedInstitution institution(
            Long connectionId,
            String organization,
            String resultCode,
            EncryptedBankAccountCredentials encrypted) {
        return new ConnectedInstitution(
                null,
                connectionId,
                organization,
                ExternalConnectionStatus.CONNECTED,
                resultCode,
                encrypted == null ? null : encrypted.localCiphertext(),
                encrypted == null ? null : encrypted.kmsCiphertext());
    }

    record RegisteredBank(
            ExternalConnection connection, String birthDate, BankAccountCredentials credentials) {}
}
