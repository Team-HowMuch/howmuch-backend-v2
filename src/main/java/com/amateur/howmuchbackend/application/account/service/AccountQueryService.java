package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.AccountTransactionKey;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.policy.AccountTransactionDisplayNormalizer;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountDetailUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.GetAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountDetailResult;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountSummaryResult;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionResult;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionRefreshStorePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.AccountSort;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountQueryService
        implements GetAccountsUseCase, GetAccountDetailUseCase, GetAccountTransactionsUseCase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String DEMAND_DEPOSIT = "11";
    private static final Duration CURRENT_MONTH_REFRESH_TTL = Duration.ofMinutes(1);

    private final CurrentActorPort currentActorPort;
    private final LoadAccountPort loadAccountPort;
    private final LoadAccountTransactionPort loadTransactionPort;
    private final SaveAccountTransactionPort saveTransactionPort;
    private final AccountTransactionRefreshStorePort refreshStorePort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final BankTransactionPort bankTransactionPort;
    private final BankCatalog bankCatalog;
    private final AccountCredentialProtectionService credentialProtection;
    private final com.amateur.howmuchbackend.application.port.out.settlement.netting
                    .QueueNettedDepositDetectionPort
            nettingDetection;
    private final AccountTransactionDisplayNormalizer transactionDisplayNormalizer;
    private final Clock clock;

    @Override
    public List<AccountSummaryResult> getAccounts(GetAccountsQuery query) {
        Long userId = currentActorPort.getCurrentActor().userId();
        List<Account> accounts = loadAccountPort.findActiveByUserId(userId).stream()
                .filter(account -> query.organization() == null
                        || query.organization().isBlank()
                        || query.organization().equals(account.organization()))
                .toList();
        Map<Long, LocalDateTime> lastUsedAt = loadTransactionPort.findLastUsedAtByAccountIds(
                accounts.stream().map(Account::id).toList()
        );
        Map<String, BankCatalogResult> institutions = institutions();
        Comparator<AccountView> comparator = defaultComparator();
        if (query.sort() == AccountSort.RECENT) {
            comparator = Comparator.comparing(
                    AccountView::lastUsedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
            ).thenComparing(defaultComparator());
        }
        return accounts.stream()
                .map(account -> new AccountView(account, lastUsedAt.get(account.id())))
                .sorted(comparator)
                .map(view -> toSummary(view, institutions.get(view.account().organization())))
                .toList();
    }

    @Override
    @Transactional
    public AccountDetailResult getAccountDetail(Long accountId, YearMonth requestedYearMonth) {
        Account account = ownedAccount(accountId);
        LocalDate today = today();
        YearMonth yearMonth = yearMonth(requestedYearMonth, today);
        AccountMonthlySummary summary = summary(account, yearMonth, today);
        BankCatalogResult institution = institutions().get(account.organization());
        return new AccountDetailResult(
                account.id(),
                account.organization(),
                institutionName(account, institution),
                institution == null ? null : institution.iconUrl(),
                account.name(),
                account.maskedNumber(),
                account.isDefault(),
                account.displayOrder(),
                yearMonth,
                summary.monthlyExpenseAmount(),
                summary.expenseCount(),
                summary.lastUsedAt()
        );
    }

    @Override
    @Transactional
    public List<AccountTransactionResult> getAccountTransactions(Long accountId, YearMonth requestedYearMonth) {
        Account account = ownedAccount(accountId);
        LocalDate today = today();
        YearMonth yearMonth = yearMonth(requestedYearMonth, today);
        summary(account, yearMonth, today);
        List<Transaction> transactions = loadTransactionPort.findByAccountIdAndYearMonth(
                account.id(),
                yearMonth
        );
        Map<String, List<LoadAccountTransactionPort.LinkedSettlement>> settlementsByTransactionKey =
                        loadTransactionPort.findLinkedSettlements(
                                account.id(),
                                transactions.stream()
                                        .map(Transaction::externalTransactionKey)
                                        .toList()
                        ).stream()
                        .collect(Collectors.groupingBy(
                                LoadAccountTransactionPort.LinkedSettlement::externalTransactionKey
                        ));
        return transactions.stream()
                .map(transaction -> AccountTransactionResult.from(
                        transaction,
                        transactionDisplayNormalizer.normalize(
                                account.organization(), transaction.description(), transaction.type(),
                                transaction.description1(), transaction.description2(), transaction.description3(),
                                transaction.description4()
                        ),
                        settlementsByTransactionKey
                                .getOrDefault(
                                        transaction.externalTransactionKey(),
                                        List.of()
                                ).stream()
                                .map(link -> new AccountTransactionResult.SettlementSummary(
                                        link.settlementId(),
                                        link.title(),
                                        link.status()
                                ))
                                .toList()
                ))
                .toList();
    }

    private Account ownedAccount(Long accountId) {
        Long userId = currentActorPort.getCurrentActor().userId();
        return loadAccountPort.findActiveByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
    }

    private AccountMonthlySummary summary(Account account, YearMonth yearMonth, LocalDate today) {
        YearMonth currentMonth = YearMonth.from(today);
        var existing = loadTransactionPort.findSummary(account.id(), yearMonth);
        if (existing.isPresent()
                && (!yearMonth.equals(currentMonth) || refreshStorePort.isFresh(account.id(), yearMonth))) {
            return existing.get();
        }
        boolean refreshMarkerRequired = yearMonth.equals(currentMonth);
        AccountMonthlySummary refreshed = refreshSummary(account, yearMonth, today);
        if (refreshMarkerRequired) {
            markRefreshFreshAfterCommit(account.id(), yearMonth);
        }
        return refreshed;
    }

    private void markRefreshFreshAfterCommit(Long accountId, YearMonth yearMonth) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshStorePort.markFresh(accountId, yearMonth, CURRENT_MONTH_REFRESH_TTL);
            }
        });
    }

    private AccountMonthlySummary refreshSummary(Account account, YearMonth yearMonth, LocalDate today) {
        if (account.productCode() != null && !DEMAND_DEPOSIT.equals(account.productCode())) {
            throw ApplicationException.of(AccountStatusCode.ACCOUNT_TRANSACTION_NOT_SUPPORTED);
        }
        ExternalConnection connection = connectionStorePort.findActiveByIdAndUserId(
                        account.externalConnectionId(), account.userId()
                )
                .orElseThrow(() -> ApplicationException.of(
                        ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND
                ));
        Account activeAccount = loadAccountPort.findActiveByIdAndUserIdForUpdate(
                        account.id(), account.userId()
                )
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        BankAccountCredentials credentials = bankCatalog.requiresAccountCredentials(activeAccount.organization())
                ? credentialProtection.reveal(account.userId(), connection.id(), activeAccount.organization()) : null;
        LocalDate endDate = yearMonth.equals(YearMonth.from(today))
                ? today
                : yearMonth.atEndOfMonth();
        List<Transaction> transactions = bankTransactionPort.fetch(
                        connection.connectedId(),
                        activeAccount.organization(),
                        activeAccount.externalAccountKey(),
                        yearMonth.atDay(1),
                        endDate,
                        credentials
                ).stream()
                .map(transaction -> new Transaction(
                        null,
                        activeAccount.id(),
                        AccountTransactionKey.from(activeAccount.externalAccountKey(), transaction),
                        transaction.type(),
                        transaction.amount(),
                        transaction.transactedAt(),
                        transaction.description(),
                        transaction.afterBalance(),
                        transaction.description1(),
                        transaction.description2(),
                        transaction.description3(),
                        transaction.description4()
                ))
                .toList();
        saveTransactionPort.saveAllIgnoringDuplicates(transactions);
        if (!transactions.isEmpty())
            nettingDetection.observed(
                    activeAccount.id(),
                    LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
        AccountMonthlySummary calculated = loadTransactionPort.calculateSummary(activeAccount.id(), yearMonth);
        saveTransactionPort.saveSummaryIfAbsent(calculated);
        return loadTransactionPort.findSummary(activeAccount.id(), yearMonth).orElse(calculated);
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

    private Map<String, BankCatalogResult> institutions() {
        return bankCatalog.getAllBanks().stream().collect(Collectors.toMap(
                BankCatalogResult::organization,
                Function.identity()
        ));
    }

    private Comparator<AccountView> defaultComparator() {
        return Comparator.comparing((AccountView view) -> view.account().isDefault()).reversed()
                .thenComparingInt(view -> view.account().displayOrder())
                .thenComparing(view -> view.account().id());
    }

    private AccountSummaryResult toSummary(AccountView view, BankCatalogResult institution) {
        Account account = view.account();
        return new AccountSummaryResult(
                account.id(),
                account.organization(),
                institutionName(account, institution),
                institution == null ? null : institution.iconUrl(),
                account.name(),
                account.maskedNumber(),
                account.isDefault(),
                account.displayOrder(),
                view.lastUsedAt()
        );
    }

    private String institutionName(Account account, BankCatalogResult institution) {
        return institution == null ? account.organization() : institution.name();
    }

    private record AccountView(Account account, LocalDateTime lastUsedAt) {
    }
}
