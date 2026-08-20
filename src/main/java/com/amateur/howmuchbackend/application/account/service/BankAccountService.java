package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.ConnectBankUseCase;
import com.amateur.howmuchbackend.application.port.in.account.LinkBankAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.ConnectBankCommand;
import com.amateur.howmuchbackend.application.port.in.account.command.LinkBankAccountsCommand;
import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionResult;
import com.amateur.howmuchbackend.application.port.in.account.result.LinkedAccountsResult;
import com.amateur.howmuchbackend.application.port.out.account.AccountStorePort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort.BankRegistration;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort.ExternalAccountCandidate;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.external.ConnectedInstitution;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BankAccountService implements ConnectBankUseCase, LinkBankAccountsUseCase {
    private static final DateTimeFormatter CODEF_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final AccountStorePort accountStorePort;
    private final BankConnectionPort bankConnectionPort;

    @Override
    public BankConnectionResult connect(ConnectBankCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        User user = loadUser(actor.userId());
        connectionStorePort.validateStorageConfiguration();
        String birthDate = birthDate(command.birthDate(), user);
        BankRegistration registration = new BankRegistration(
                command.organization(), command.loginId(), command.password(), birthDate
        );

        ExternalConnection connection = connectionStorePort.findActiveByUserId(actor.userId()).orElse(null);
        if (connection == null) {
            var result = bankConnectionPort.create(registration);
            connection = connectionStorePort.saveNew(
                    new ExternalConnection(null, actor.userId(), result.connectedId()),
                    institution(null, command.organization(), result.resultCode())
            );
        } else if (!connectionStorePort.hasInstitution(connection.id(), command.organization())) {
            var result = bankConnectionPort.add(connection.connectedId(), registration);
            connectionStorePort.saveInstitution(institution(connection.id(), command.organization(), result.resultCode()));
        }

        Set<String> linkedKeys = accountStorePort.findCandidateKeys(connection.id());
        String connectedId = connection.connectedId();
        List<BankConnectionResult.Candidate> candidates = bankConnectionPort
                .findAccounts(connectedId, command.organization(), birthDate)
                .stream()
                .map(candidate -> toResultCandidate(connectedId, command.organization(), candidate, linkedKeys))
                .toList();
        return new BankConnectionResult(
                connection.id(), command.organization(), ExternalConnectionStatus.CONNECTED, candidates
        );
    }

    @Override
    public LinkedAccountsResult link(LinkBankAccountsCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        User user = loadUser(actor.userId());
        connectionStorePort.validateStorageConfiguration();
        ExternalConnection connection = connectionStorePort.findActiveByIdAndUserId(
                        command.connectionId(), actor.userId()
                )
                .orElseThrow(() -> ApplicationException.of(
                        ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND
                ));
        if (!connectionStorePort.hasInstitution(connection.id(), command.organization())) {
            throw ApplicationException.of(ExternalConnectionStatusCode.ACCOUNT_CANDIDATE_INVALID);
        }

        Map<String, ExternalAccountCandidate> candidates = new LinkedHashMap<>();
        for (ExternalAccountCandidate candidate : bankConnectionPort.findAccounts(
                connection.connectedId(), command.organization(), birthDate(command.birthDate(), user)
        )) {
            candidates.put(candidateKey(connection.connectedId(), command.organization(), candidate.externalAccountKey()), candidate);
        }

        List<Account> selected = new ArrayList<>();
        for (String selectedKey : command.candidateKeys().stream().distinct().toList()) {
            ExternalAccountCandidate candidate = candidates.get(selectedKey);
            if (candidate == null) {
                throw ApplicationException.of(ExternalConnectionStatusCode.ACCOUNT_CANDIDATE_INVALID);
            }
            selected.add(new Account(
                    null,
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
                    true
            ));
        }

        List<LinkedAccountsResult.LinkedAccount> linked = accountStorePort.saveAll(selected).stream()
                .map(account -> new LinkedAccountsResult.LinkedAccount(
                        account.id(), account.organization(), account.type(), account.name(), account.maskedNumber(),
                        account.balance(), account.currency()
                ))
                .toList();
        return new LinkedAccountsResult(linked);
    }

    private User loadUser(Long userId) {
        return loadUserPort.findById(userId)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
    }

    private String birthDate(String requested, User user) {
        return requested != null ? requested : user.birthDate() == null ? null : CODEF_DATE.format(user.birthDate());
    }

    private ConnectedInstitution institution(Long connectionId, String organization, String resultCode) {
        return new ConnectedInstitution(
                null, connectionId, organization, ExternalConnectionStatus.CONNECTED, resultCode
        );
    }

    private BankConnectionResult.Candidate toResultCandidate(
            String connectedId,
            String organization,
            ExternalAccountCandidate candidate,
            Set<String> linkedKeys
    ) {
        String candidateKey = candidateKey(connectedId, organization, candidate.externalAccountKey());
        return new BankConnectionResult.Candidate(
                candidateKey,
                candidate.type(),
                candidate.name(),
                candidate.maskedNumber(),
                candidate.balance(),
                candidate.currency(),
                linkedKeys.contains(candidateKey)
        );
    }

    private String candidateKey(String connectedId, String organization, String externalAccountKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(connectedId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(organization.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    digest.digest(externalAccountKey.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available.", impossible);
        }
    }
}
