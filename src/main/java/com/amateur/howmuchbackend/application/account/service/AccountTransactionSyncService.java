package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionSyncJobUseCase;
import com.amateur.howmuchbackend.application.port.in.account.RequestAccountTransactionSyncUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.RequestAccountTransactionSyncCommand;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionSyncJobResult;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncEligibilityPort;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncJobStorePort;
import com.amateur.howmuchbackend.application.port.out.account.FinanceSyncReferenceStorePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.external.FinanceConnectorRegistrationPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncJob;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountTransactionSyncService
        implements RequestAccountTransactionSyncUseCase, GetAccountTransactionSyncJobUseCase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CurrentActorPort currentActorPort;
    private final LoadAccountPort loadAccountPort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final AccountTransactionSyncEligibilityPort eligibilityPort;
    private final FinanceSyncReferenceStorePort referenceStorePort;
    private final FinanceConnectorRegistrationPort connectorRegistrationPort;
    private final AccountTransactionSyncJobStorePort jobStorePort;
    private final Clock clock;

    @Override
    public AccountTransactionSyncJobResult request(RequestAccountTransactionSyncCommand command) {
        validate(command);
        Long userId = currentActorPort.getCurrentActor().userId();
        String requestHash = requestHash(command);
        var existing = jobStorePort.findByIdempotencyKey(userId, command.idempotencyKey());
        if (existing.isPresent()) {
            return AccountTransactionSyncJobResult.from(requireSameRequest(existing.get(), requestHash));
        }

        Account account = loadAccountPort.findActiveByIdAndUserId(command.accountId(), userId)
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        if (!eligibilityPort.isEnabled(account.id())) {
            throw ApplicationException.of(AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_NOT_ENABLED);
        }
        if (!account.isActiveWonDemandDeposit() || !hasConnectorRequiredFields(account)) {
            throw ApplicationException.of(AccountStatusCode.ACCOUNT_TRANSACTION_NOT_SUPPORTED);
        }
        if (jobStorePort.hasActiveJob(account.id())) {
            throw ApplicationException.of(AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_ALREADY_RUNNING);
        }
        ExternalConnection connection = connectionStorePort.findActiveByIdAndUserId(
                        account.externalConnectionId(), userId
                )
                .orElseThrow(() -> ApplicationException.of(
                        ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND
                ));
        List<Account> registeredAccounts = loadAccountPort
                .findActiveByConnectionAndOrganization(connection.id(), userId, account.organization())
                .stream()
                .filter(this::hasConnectorRequiredFields)
                .toList();
        var references = referenceStorePort.prepare(
                connection.id(), registeredAccounts.stream().map(Account::id).toList()
        );
        long connectionVersion = connectorRegistrationPort.register(
                new FinanceConnectorRegistrationPort.Registration(
                        references.connectionRef(),
                        connection.connectedId(),
                        account.organization(),
                        registeredAccounts.stream()
                                .map(registered -> new FinanceConnectorRegistrationPort.Account(
                                        references.accountRef(registered.id()),
                                        registered.externalAccountKey(),
                                        registered.type(),
                                        registered.productCode(),
                                        registered.currency().toUpperCase(Locale.ROOT)
                                ))
                                .toList()
                )
        ).connectionVersion();

        var result = jobStorePort.enqueue(new AccountTransactionSyncJobStorePort.EnqueueCommand(
                userId,
                account.id(),
                connection.id(),
                references.connectionRef(),
                references.accountRef(account.id()),
                connectionVersion,
                command.idempotencyKey(),
                requestHash,
                command.fromDate(),
                command.toDate(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        ));
        return switch (result.outcome()) {
            case CREATED -> AccountTransactionSyncJobResult.from(result.job());
            case EXISTING -> AccountTransactionSyncJobResult.from(
                    requireSameRequest(result.job(), requestHash)
            );
            case ACTIVE_ACCOUNT_CONFLICT -> throw ApplicationException.of(
                    AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_ALREADY_RUNNING
            );
            case ACCOUNT_UNAVAILABLE -> throw ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND);
            case STALE_REGISTRATION -> throw ApplicationException.of(
                    AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_CONNECTOR_UNAVAILABLE
            );
        };
    }

    @Override
    public AccountTransactionSyncJobResult get(UUID syncJobId) {
        Long userId = currentActorPort.getCurrentActor().userId();
        return jobStorePort.findByIdAndUserId(syncJobId, userId)
                .map(AccountTransactionSyncJobResult::from)
                .orElseThrow(() -> ApplicationException.of(
                        AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_JOB_NOT_FOUND
                ));
    }

    private void validate(RequestAccountTransactionSyncCommand command) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        if (command.accountId() == null
                || command.accountId() < 1
                || command.idempotencyKey() == null
                || command.fromDate() == null
                || command.toDate() == null
                || command.fromDate().isAfter(command.toDate())
                || command.toDate().isAfter(today)
                || ChronoUnit.DAYS.between(command.fromDate(), command.toDate()) > 30) {
            throw ApplicationException.of(AccountStatusCode.INVALID_ACCOUNT_TRANSACTION_SYNC_REQUEST);
        }
    }

    private AccountTransactionSyncJob requireSameRequest(
            AccountTransactionSyncJob existing,
            String requestHash
    ) {
        if (!existing.requestHash().equals(requestHash)) {
            throw ApplicationException.of(AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_IDEMPOTENCY_CONFLICT);
        }
        return existing;
    }

    private String requestHash(RequestAccountTransactionSyncCommand command) {
        try {
            String value = command.accountId() + "|" + command.fromDate() + "|" + command.toDate();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private boolean hasConnectorRequiredFields(Account account) {
        return account.externalAccountKey() != null
                && !account.externalAccountKey().isBlank()
                && account.productCode() != null
                && !account.productCode().isBlank()
                && account.currency() != null
                && account.currency().matches("[A-Za-z]{3}");
    }
}
