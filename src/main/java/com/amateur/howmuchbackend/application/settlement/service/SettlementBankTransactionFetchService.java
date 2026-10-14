package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.account.AccountTransactionKey;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.service.AccountCredentialProtectionService;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SettlementBankTransactionFetchService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final BankTransactionPort bankTransactionPort;
    private final BankCatalog bankCatalog;
    private final AccountCredentialProtectionService credentialProtection;

    /** Caller releases all database locks before invoking this external conversation. */
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
            month = month.plusMonths(1);
        }
        return List.copyOf(transactions);
    }

    private LocalDate seoulDate(LocalDateTime utcDateTime) {
        return utcDateTime.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(SEOUL)
                .toLocalDate();
    }
}
