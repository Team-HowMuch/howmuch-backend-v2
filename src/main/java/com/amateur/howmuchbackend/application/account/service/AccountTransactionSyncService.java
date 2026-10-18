package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.AccountTransactionKey;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.port.in.account.SyncAccountTransactionsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionSyncResult;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionCoveragePort;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionRefreshStorePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Explicit account transaction synchronization.
 *
 * <p>Read endpoints never call CODEF. A caller that needs fresh data invokes this use case; the
 * default external conversation happens outside a database transaction and the resulting rows are
 * written by {@link AccountTransactionSyncWriter}. Opt-in SMS authentication keeps its existing
 * transaction-scoped cross-operation admission during the initial provider call.
 *
 * <p>A short Redis claim suppresses concurrent duplicate CODEF calls for the same account-month.
 * The claim is best-effort deduplication: the database write still revalidates the account, so an
 * expired claim cannot corrupt data even if two synchronizations overlap.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountTransactionSyncService implements SyncAccountTransactionsUseCase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String DEMAND_DEPOSIT = "11";
    private static final Duration REFRESH_THROTTLE = Duration.ofMinutes(1);
    private static final Duration SYNC_CLAIM_TTL = Duration.ofMinutes(6);
    private static final Duration COVERAGE_TTL = Duration.ofDays(30);
    /** Sentinel token used when the claim store is unavailable and synchronization should continue. */
    private static final String UNCLAIMED = "";

    private final CurrentActorPort currentActorPort;
    private final LoadAccountPort loadAccountPort;
    private final LoadAccountTransactionPort loadTransactionPort;
    private final AccountTransactionRefreshStorePort refreshStorePort;
    private final AccountTransactionCoveragePort coveragePort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final BankTransactionPort bankTransactionPort;
    private final BankCatalog bankCatalog;
    private final AccountCredentialProtectionService credentialProtection;
    private final AccountTransactionSyncWriter writer;
    private final Clock clock;

    private com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService authentication;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void authentication(com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService value) {
        authentication = value;
    }

    @Override
    public AccountTransactionSyncResult sync(Long accountId, YearMonth requestedYearMonth) {
        Long userId = currentActorPort.getCurrentActor().userId();
        Account account = loadAccountPort.findActiveByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        ensureTransactionSupported(account);

        LocalDate today = today();
        YearMonth yearMonth = yearMonth(requestedYearMonth, today);
        if (isFresh(accountId, yearMonth)) {
            return storedResult(account, yearMonth, false, false);
        }

        String claimToken = claim(accountId, yearMonth);
        if (claimToken == null) {
            return storedResult(account, yearMonth, false, true);
        }

        try {
            ExternalConnection connection = connectionStorePort.findActiveByIdAndUserId(
                            account.externalConnectionId(), userId
                    )
                    .orElseThrow(() -> ApplicationException.of(
                            ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND
                    ));
            BankAccountCredentials credentials = bankCatalog.requiresAccountCredentials(account.organization())
                    ? credentialProtection.reveal(userId, connection.id(), account.organization())
                    : null;

            LocalDate from = yearMonth.atDay(1);
            LocalDate to = yearMonth.equals(YearMonth.from(today)) ? today : yearMonth.atEndOfMonth();
            LocalDateTime observedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            java.util.function.Supplier<List<BankTransactionPort.BankTransaction>> query = () -> {
                try {
                    return bankTransactionPort.fetch(connection.connectedId(), account.organization(),
                            account.externalAccountKey(), from, to, credentials);
                } catch (com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationRequiredException required) {
                    if (authentication == null) throw ApplicationException.of(
                            ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED);
                    var operation = new com.amateur.howmuchbackend.application.external.model.FinanceOperation(
                            "TRANSACTION_LIST", account.organization(), connection.id(), account.id(),
                            yearMonth.toString(), null, false, observedAt, to);
                    throw new com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationPendingException(
                            authentication.suspendOperation(userId, operation, required.request()));
                }
            };
            // SMS admission retains the existing cross-operation advisory lock until its provider call ends.
            var fetched = authentication == null ? query.get()
                    : authentication.withTransactionQueryAdmission(userId, account.organization(), query);
            List<Transaction> transactions = transactions(account, fetched);

            AccountMonthlySummary summary = writer.persist(
                    userId, account.id(), yearMonth, transactions, observedAt
            );
            markCollectedAfterCommit(account.id(), yearMonth, from, to, observedAt);

            return new AccountTransactionSyncResult(
                    account.id(),
                    yearMonth,
                    true,
                    false,
                    observedAt,
                    from,
                    to,
                    transactions.size(),
                    summary.monthlyExpenseAmount(),
                    summary.expenseCount(),
                    summary.lastUsedAt()
            );
        } finally {
            release(accountId, yearMonth, claimToken);
        }
    }

    public void completeAdditionalAuthentication(long user,
            com.amateur.howmuchbackend.application.external.model.FinanceOperation operation,
            List<BankTransactionPort.BankTransaction> fetched) {
        Account account = loadAccountPort.findActiveByIdAndUserId(operation.accountId(), user)
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        if (!account.organization().equals(operation.organization())
                || !account.externalConnectionId().equals(operation.connectionId()))
            throw ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND);
        connectionStorePort.findActiveByIdAndUserId(operation.connectionId(), user)
                .orElseThrow(() -> ApplicationException.of(ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND));
        YearMonth month = YearMonth.parse(operation.yearMonth());
        LocalDateTime observed = operation.collectedAt() == null
                ? LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC) : operation.collectedAt();
        writer.persist(user, account.id(), month, transactions(account, fetched), observed);
        // Legacy pending GET payloads did not carry a range; do not invent coverage for them.
        if (operation.collectedAt() != null && operation.collectedThrough() != null) {
            markCollectedAfterCommit(account.id(), month, month.atDay(1), operation.collectedThrough(), observed);
        }
    }

    private List<Transaction> transactions(Account account, List<BankTransactionPort.BankTransaction> fetched) {
        return fetched.stream().map(transaction -> new Transaction(null, account.id(),
                AccountTransactionKey.from(account.externalAccountKey(), transaction), transaction.type(),
                transaction.amount(), transaction.transactedAt(), transaction.description(), transaction.afterBalance(),
                transaction.description1(), transaction.description2(), transaction.description3(), transaction.description4())).toList();
    }

    private void markCollectedAfterCommit(Long accountId, YearMonth month, LocalDate from, LocalDate to, LocalDateTime at) {
        Runnable mark = () -> { markFresh(accountId, month); markCovered(accountId, month, from, to, at); };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { mark.run(); }
                    });
        } else mark.run();
    }

    private AccountTransactionSyncResult storedResult(
            Account account,
            YearMonth yearMonth,
            boolean refreshed,
            boolean syncInProgress
    ) {
        AccountMonthlySummary summary = loadTransactionPort.findSummary(account.id(), yearMonth)
                .orElseGet(() -> new AccountMonthlySummary(
                        null, account.id(), yearMonth, BigDecimal.ZERO, 0, null
                ));
        return new AccountTransactionSyncResult(
                account.id(),
                yearMonth,
                refreshed,
                syncInProgress,
                lastSyncedAt(account.id(), yearMonth),
                null,
                null,
                0,
                summary.monthlyExpenseAmount(),
                summary.expenseCount(),
                summary.lastUsedAt()
        );
    }

    private LocalDateTime lastSyncedAt(Long accountId, YearMonth yearMonth) {
        try {
            return coveragePort.findCoveredAt(accountId, yearMonth).orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean isFresh(Long accountId, YearMonth yearMonth) {
        try {
            return refreshStorePort.isFresh(accountId, yearMonth)
                    && lastSyncedAt(accountId, yearMonth) != null
                    && (yearMonth.equals(YearMonth.from(today())) || coveragePort.coversMonth(accountId, yearMonth));
        } catch (RuntimeException exception) {
            log.warn("Failed to read account transaction refresh marker. accountId={} yearMonth={}",
                    accountId, yearMonth, exception);
            return false;
        }
    }

    private String claim(Long accountId, YearMonth yearMonth) {
        try {
            Optional<String> token = refreshStorePort.tryClaim(accountId, yearMonth, SYNC_CLAIM_TTL);
            return token.orElse(null);
        } catch (RuntimeException exception) {
            log.warn("Failed to claim account transaction sync. Continuing without claim. "
                    + "accountId={} yearMonth={}", accountId, yearMonth, exception);
            return UNCLAIMED;
        }
    }

    private void release(Long accountId, YearMonth yearMonth, String token) {
        if (UNCLAIMED.equals(token)) {
            return;
        }
        try {
            refreshStorePort.release(accountId, yearMonth, token);
        } catch (RuntimeException exception) {
            log.warn("Failed to release account transaction sync claim. accountId={} yearMonth={}",
                    accountId, yearMonth, exception);
        }
    }

    private void markFresh(Long accountId, YearMonth yearMonth) {
        try {
            refreshStorePort.markFresh(accountId, yearMonth, REFRESH_THROTTLE);
        } catch (RuntimeException exception) {
            log.warn("Failed to write account transaction refresh marker. accountId={} yearMonth={}",
                    accountId, yearMonth, exception);
        }
    }

    private void markCovered(Long accountId, YearMonth yearMonth, LocalDate from, LocalDate through, LocalDateTime fetchedAt) {
        try {
            coveragePort.markCovered(accountId, yearMonth, from, through, fetchedAt, COVERAGE_TTL);
        } catch (RuntimeException exception) {
            log.warn("Failed to write account transaction coverage. accountId={} yearMonth={}",
                    accountId, yearMonth, exception);
        }
    }

    private void ensureTransactionSupported(Account account) {
        if (account.productCode() != null && !DEMAND_DEPOSIT.equals(account.productCode())) {
            throw ApplicationException.of(AccountStatusCode.ACCOUNT_TRANSACTION_NOT_SUPPORTED);
        }
    }

    private YearMonth yearMonth(YearMonth requested, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        YearMonth result = requested == null ? current : requested;
        if (result.isAfter(current)) {
            throw ApplicationException.of(AccountStatusCode.INVALID_ACCOUNT_QUERY);
        }
        return result;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }
}
