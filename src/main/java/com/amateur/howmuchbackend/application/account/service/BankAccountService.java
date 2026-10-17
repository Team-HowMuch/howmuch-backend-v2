package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.ConnectBankUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetBankConnectionAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.LinkBankAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.ConnectBankCommand;
import com.amateur.howmuchbackend.application.port.in.account.command.GetBankConnectionAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.command.LinkBankAccountsCommand;
import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionResult;
import com.amateur.howmuchbackend.application.port.in.account.result.LinkedAccountsResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort.ExternalAccountCandidate;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.user.User;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BankAccountService
        implements ConnectBankUseCase, LinkBankAccountsUseCase, GetBankConnectionAccountsUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final LoadAccountPort loadAccountPort;
    private final SaveAccountPort saveAccountPort;
    private final BankConnectionPort bankConnectionPort;
    private final BankCatalog bankCatalog;
    private final AccountCredentialProtectionService credentialProtection;
    private final BankConnectionRegistrationService registrationService;

    private com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService
            authentication;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void authentication(
            com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService
                    value) {
        authentication = value;
    }

    @Override
    public BankConnectionResult connect(ConnectBankCommand command) {
        var registered = registrationService.register(command);
        ExternalConnection connection = registered.connection();
        Set<String> linkedKeys = loadAccountPort.findCandidateKeys(connection.id());
        String connectedId = connection.connectedId();
        List<BankConnectionResult.Candidate> candidates =
                findAccounts(
                                connection,
                                command.organization(),
                                registered.birthDate(),
                                registered.credentials())
                        .stream()
                        .map(
                                candidate ->
                                        toResultCandidate(
                                                connectedId,
                                                command.organization(),
                                                candidate,
                                                linkedKeys))
                        .toList();
        return new BankConnectionResult(
                connection.id(),
                command.organization(),
                ExternalConnectionStatus.CONNECTED,
                candidates);
    }

    @Override
    public BankConnectionResult getConnectionAccounts(GetBankConnectionAccountsQuery query) {
        Long userId = currentActorPort.getCurrentActor().userId();
        ExternalConnection connection =
                connectionStorePort
                        .findActiveByUserId(userId)
                        .filter(owned -> owned.id().equals(query.connectionId()))
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                ExternalConnectionStatusCode
                                                        .EXTERNAL_CONNECTION_NOT_FOUND));
        if (!connectionStorePort.hasInstitution(connection.id(), query.organization())) {
            throw ApplicationException.of(ExternalConnectionStatusCode.ACCOUNT_CANDIDATE_INVALID);
        }
        BankAccountCredentials credentials =
                bankCatalog.requiresAccountCredentials(query.organization())
                        ? credentialProtection.reveal(userId, connection.id(), query.organization())
                        : null;
        Set<String> linkedKeys = loadAccountPort.findCandidateKeys(connection.id());
        var candidates =
                findAccounts(connection, query.organization(), query.birthDate(), credentials)
                        .stream()
                        .map(
                                candidate ->
                                        toResultCandidate(
                                                connection.connectedId(),
                                                query.organization(),
                                                candidate,
                                                linkedKeys))
                        .toList();
        return new BankConnectionResult(
                connection.id(),
                query.organization(),
                ExternalConnectionStatus.CONNECTED,
                candidates);
    }

    @Override
    @Transactional(
            noRollbackFor =
                    com.amateur.howmuchbackend.application.external.model
                            .AdditionalAuthenticationPendingException.class)
    public LinkedAccountsResult link(LinkBankAccountsCommand command) {
        bankCatalog.validate(command.organization());
        CurrentActor actor = currentActorPort.getCurrentActor();
        loadUserForUpdate(actor.userId());
        connectionStorePort.validateStorageConfiguration();
        ExternalConnection connection =
                connectionStorePort
                        .findActiveByIdAndUserId(command.connectionId(), actor.userId())
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                ExternalConnectionStatusCode
                                                        .EXTERNAL_CONNECTION_NOT_FOUND));
        if (!connectionStorePort.hasInstitution(connection.id(), command.organization())) {
            throw ApplicationException.of(ExternalConnectionStatusCode.ACCOUNT_CANDIDATE_INVALID);
        }
        BankAccountCredentials credentials =
                bankCatalog.requiresAccountCredentials(command.organization())
                        ? credentialProtection.reveal(
                                actor.userId(), connection.id(), command.organization())
                        : null;

        Map<String, ExternalAccountCandidate> candidates = new LinkedHashMap<>();
        for (ExternalAccountCandidate candidate :
                findAccounts(
                        connection, command.organization(), command.birthDate(), credentials)) {
            candidates.put(
                    candidateKey(
                            connection.connectedId(),
                            command.organization(),
                            candidate.externalAccountKey()),
                    candidate);
        }

        List<Account> selected = new ArrayList<>();
        List<Account> existingAccounts = loadAccountPort.findActiveByUserId(actor.userId());
        Map<String, Account> existingByCandidateKey =
                existingAccounts.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Account::candidateKey, account -> account));
        int nextDisplayOrder =
                existingAccounts.stream().mapToInt(Account::displayOrder).max().orElse(-1) + 1;
        for (String selectedKey : command.candidateKeys().stream().distinct().toList()) {
            ExternalAccountCandidate candidate = candidates.get(selectedKey);
            if (candidate == null) {
                throw ApplicationException.of(
                        ExternalConnectionStatusCode.ACCOUNT_CANDIDATE_INVALID);
            }
            Account existing = existingByCandidateKey.get(selectedKey);
            selected.add(
                    new Account(
                            existing == null ? null : existing.id(),
                            actor.userId(),
                            connection.id(),
                            command.organization(),
                            selectedKey,
                            candidate.externalAccountKey(),
                            candidate.type(),
                            candidate.name(),
                            candidate.maskedNumber(),
                            candidate.balance(),
                            candidate.currency(),
                            true,
                            candidate.productCode(),
                            existing == null
                                    ? existingAccounts.isEmpty() && selected.isEmpty()
                                    : existing.isDefault(),
                            existing == null ? nextDisplayOrder++ : existing.displayOrder()));
        }

        List<LinkedAccountsResult.LinkedAccount> linked =
                saveAccountPort.saveAll(selected).stream()
                        .map(
                                account ->
                                        new LinkedAccountsResult.LinkedAccount(
                                                account.id(),
                                                account.organization(),
                                                account.type(),
                                                account.name(),
                                                account.maskedNumber(),
                                                account.balance(),
                                                account.currency()))
                        .toList();
        return new LinkedAccountsResult(linked);
    }

    private List<ExternalAccountCandidate> findAccounts(
            ExternalConnection connection,
            String organization,
            String birthDate,
            BankAccountCredentials credentials) {
        if (authentication == null)
            return findAccountsAdmitted(connection, organization, birthDate, credentials);
        return authentication.withAccountQueryAdmission(
                connection.userId(), organization,
                () -> findAccountsAdmitted(connection, organization, birthDate, credentials));
    }

    private List<ExternalAccountCandidate> findAccountsAdmitted(
            ExternalConnection connection,
            String organization,
            String birthDate,
            BankAccountCredentials credentials) {
        if (authentication != null) {
            var cached =
                    authentication.cachedAccounts(
                            connection.userId(), connection.id(), organization);
            if (cached.isPresent()) return cached.get().candidates();
        }
        try {
            return bankConnectionPort.findAccounts(
                    connection.connectedId(), organization, birthDate, credentials);
        } catch (
                com.amateur.howmuchbackend.application.external.model
                                .AdditionalAuthenticationRequiredException
                        required) {
            if (authentication == null)
                throw ApplicationException.of(
                        ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED);
            var operation =
                    new com.amateur.howmuchbackend.application.external.model.FinanceOperation(
                            "ACCOUNT_LIST", organization, connection.id(), null, null, null, false);
            throw new com.amateur.howmuchbackend.application.external.model
                    .AdditionalAuthenticationPendingException(
                    authentication.suspendOperation(
                            connection.userId(), operation, required.request()));
        }
    }

    public BankConnectionResult describeCandidates(
            long user,
            Long connectionId,
            String organization,
            List<ExternalAccountCandidate> candidates) {
        var connection =
                connectionStorePort
                        .findActiveByIdAndUserId(connectionId, user)
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                ExternalConnectionStatusCode
                                                        .EXTERNAL_CONNECTION_NOT_FOUND));
        if (!connectionStorePort.hasInstitution(connectionId, organization))
            throw ApplicationException.of(ExternalConnectionStatusCode.ACCOUNT_CANDIDATE_INVALID);
        var linked = loadAccountPort.findCandidateKeys(connectionId);
        return new BankConnectionResult(
                connectionId,
                organization,
                ExternalConnectionStatus.CONNECTED,
                candidates.stream()
                        .map(
                                c ->
                                        toResultCandidate(
                                                connection.connectedId(), organization, c, linked))
                        .toList());
    }

    private User loadUserForUpdate(Long userId) {
        return loadUserPort
                .findByIdForUpdate(userId)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
    }

    private BankConnectionResult.Candidate toResultCandidate(
            String connectedId,
            String organization,
            ExternalAccountCandidate candidate,
            Set<String> linkedKeys) {
        String candidateKey =
                candidateKey(connectedId, organization, candidate.externalAccountKey());
        return new BankConnectionResult.Candidate(
                candidateKey,
                candidate.type(),
                candidate.name(),
                candidate.maskedNumber(),
                candidate.balance(),
                candidate.currency(),
                linkedKeys.contains(candidateKey));
    }

    private String candidateKey(
            String connectedId, String organization, String externalAccountKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(connectedId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(organization.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            digest.digest(externalAccountKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available.", impossible);
        }
    }
}
