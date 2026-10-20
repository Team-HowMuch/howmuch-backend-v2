package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.account.AccountTransactionKey;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.service.AccountCredentialProtectionService;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionCoveragePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementBankLookupBudgetPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementTransferReconciliationPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches bank transactions month by month, reusing stored transactions for months already collected
 * by any caller.
 *
 * <p>Already collected months are served from PostgreSQL through {@code LoadAccountTransactionPort}
 * instead of calling CODEF again. The current month and the previous month are always re-fetched so
 * delayed postings and corrections are observed; older months are revalidated by coverage TTL.
 *
 * <p>Caller releases all database locks before invoking this external conversation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementBankTransactionFetchService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int ALWAYS_REVALIDATE_MONTHS = 1;

    private final BankTransactionPort bankTransactionPort;
    private final BankCatalog bankCatalog;
    private final AccountCredentialProtectionService credentialProtection;
    private final SettlementBankLookupBudgetPort budget;
    private final AccountTransactionCoveragePort coverage;
    private final LoadAccountTransactionPort loadTransactionPort;
    private final Clock clock;

    public List<Transaction> fetch(
            ExternalConnection connection,
            long accountId,
            String organization,
            String accountNumber,
            LocalDateTime from,
            LocalDateTime until
    ) {
        return fetch(connection, accountId, organization, accountNumber, from, until, () -> {});
    }

    public List<Transaction> fetch(
            ExternalConnection connection, long accountId, String organization, String accountNumber,
            LocalDateTime from, LocalDateTime until, Runnable beforeEachPeriod
    ) {
        return fetchWithBudget(connection, accountId, organization, accountNumber, from, until, beforeEachPeriod, false, false);
    }

    public List<Transaction> fetchAutomatically(ExternalConnection connection, long accountId,
            String organization, String accountNumber, LocalDateTime from, LocalDateTime until,
            Runnable beforeEachPeriod) {
        return fetchWithBudget(connection, accountId, organization, accountNumber, from, until, beforeEachPeriod, true, false);
    }

    public List<Transaction> fetchForWidget(ExternalConnection connection, long accountId,
            String organization, String accountNumber, LocalDateTime from, LocalDateTime until,
            Runnable beforeEachPeriod) {
        return fetchWithBudget(connection, accountId, organization, accountNumber, from, until,
                beforeEachPeriod, false, true);
    }

    private List<Transaction> fetchWithBudget(ExternalConnection connection, long accountId,
            String organization, String accountNumber, LocalDateTime from, LocalDateTime until,
            Runnable beforeEachPeriod, boolean automatic, boolean reuseRecentSnapshot) {
        LocalDate startDate = seoulDate(from);
        LocalDate endDate = seoulDate(until);
        if (startDate.isAfter(endDate)) {
            return List.of();
        }
        BankAccountCredentials credentials = bankCatalog.requiresAccountCredentials(organization)
                ? credentialProtection.reveal(
                        connection.userId(), connection.id(), organization
                )
                : null;

        YearMonth currentMonth = YearMonth.from(seoulDate(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
        YearMonth revalidateFrom = currentMonth.minusMonths(ALWAYS_REVALIDATE_MONTHS);

        List<Transaction> transactions = new ArrayList<>();
        YearMonth month = YearMonth.from(startDate);
        YearMonth lastMonth = YearMonth.from(endDate);
        while (!month.isAfter(lastMonth)) {
            LocalDate periodStart = month.equals(YearMonth.from(startDate))
                    ? startDate
                    : month.atDay(1);
            LocalDate periodEnd = month.equals(lastMonth)
                    ? endDate
                    : month.atEndOfMonth();
            beforeEachPeriod.run();
            boolean recent = reuseRecentSnapshot && recentlyCovers(accountId, month, periodStart, periodEnd);
            boolean reusable = recent || (month.isBefore(revalidateFrom) && coversMonth(accountId, month));
            if (!reusable) {
                budget.reserve(connection.userId(), automatic, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                        SettlementTransferReconciliationPolicy.DAILY_LOOKUPS,
                        SettlementTransferReconciliationPolicy.DAILY_AUTOMATIC_LOOKUPS);
                bankTransactionPort.fetch(
                        connection.connectedId(),
                        organization,
                        accountNumber,
                        periodStart,
                        periodEnd,
                        credentials
                ).stream()
                        .map(transaction -> new Transaction(
                                null,
                                accountId,
                                AccountTransactionKey.from(accountNumber, transaction),
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
                        .forEach(transactions::add);
                // The caller has not committed this partial batch. Only full account sync writes coverage.
            } else {
                transactions.addAll(loadTransactionPort.findByAccountIdAndYearMonth(accountId, month).stream()
                        .filter(transaction -> !seoulDate(transaction.transactedAt()).isBefore(periodStart)
                                && !seoulDate(transaction.transactedAt()).isAfter(periodEnd))
                        .toList());
            }
            month = month.plusMonths(1);
        }
        return List.copyOf(transactions);
    }

    private boolean recentlyCovers(long accountId, YearMonth month, LocalDate from, LocalDate through) {
        try {
            return coverage.recentlyCovers(accountId, month, from, through,
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), Duration.ofMinutes(1));
        } catch (RuntimeException exception) {
            log.warn("Failed to read recent account snapshot. accountId={} month={}", accountId, month);
            return false;
        }
    }

    private boolean coversMonth(long accountId, YearMonth month) {
        try {
            return coverage.coversMonth(accountId, month);
        } catch (RuntimeException exception) {
            log.warn("Failed to read account transaction coverage. accountId={} month={}", accountId, month, exception);
            return false;
        }
    }

    private LocalDate seoulDate(LocalDateTime utcDateTime) {
        return utcDateTime.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(SEOUL)
                .toLocalDate();
    }
}
