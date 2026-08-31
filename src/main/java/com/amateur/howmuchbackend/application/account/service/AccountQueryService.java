package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.AccountTransactionKey;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountDetailUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.GetAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountDetailResult;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountSummaryResult;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionResult;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
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

    private final CurrentActorPort currentActorPort;
    private final LoadAccountPort loadAccountPort;
    private final LoadAccountTransactionPort loadTransactionPort;
    private final SaveAccountTransactionPort saveTransactionPort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final BankTransactionPort bankTransactionPort;
    private final BankCatalog bankCatalog;

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
        YearMonth yearMonth = yearMonth(requestedYearMonth);
        AccountMonthlySummary summary = summary(account, yearMonth);
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
        YearMonth yearMonth = yearMonth(requestedYearMonth);
        summary(account, yearMonth);
        List<Transaction> transactions = loadTransactionPort.findByAccountIdAndYearMonth(
                account.id(),
                yearMonth
        );
        Map<String, List<LoadAccountTransactionPort.LinkedSettlement>> settlementsByTransactionKey =
                loadTransactionPort.findLinkedSettlements(
                                account.userId(),
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

    private AccountMonthlySummary summary(Account account, YearMonth yearMonth) {
        return loadTransactionPort.findSummary(account.id(), yearMonth).orElseGet(() -> {
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
            List<Transaction> transactions = bankTransactionPort.fetch(
                            connection.connectedId(),
                            activeAccount.organization(),
                            activeAccount.externalAccountKey(),
                            yearMonth.atDay(1),
                            yearMonth.atEndOfMonth()
                    ).stream()
                    .map(transaction -> new Transaction(
                            null,
                            activeAccount.id(),
                            AccountTransactionKey.from(activeAccount.externalAccountKey(), transaction),
                            transaction.type(),
                            transaction.amount(),
                            transaction.transactedAt(),
                            transaction.description(),
                            transaction.afterBalance()
                    ))
                    .toList();
            saveTransactionPort.saveAllIgnoringDuplicates(transactions);
            AccountMonthlySummary calculated = loadTransactionPort.calculateSummary(activeAccount.id(), yearMonth);
            saveTransactionPort.saveSummaryIfAbsent(calculated);
            return loadTransactionPort.findSummary(activeAccount.id(), yearMonth).orElse(calculated);
        });
    }

    private YearMonth yearMonth(YearMonth requested) {
        YearMonth current = YearMonth.now(SEOUL);
        YearMonth result = requested == null ? current : requested;
        if (result.isAfter(current)) {
            throw ApplicationException.of(AccountStatusCode.INVALID_ACCOUNT_QUERY);
        }
        return result;
    }

    private Map<String, BankCatalogResult> institutions() {
        return bankCatalog.getBanks().stream().collect(Collectors.toMap(
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
