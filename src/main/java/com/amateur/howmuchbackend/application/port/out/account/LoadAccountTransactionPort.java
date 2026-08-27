package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.Transaction;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LoadAccountTransactionPort {
    Map<Long, LocalDateTime> findLastUsedAtByAccountIds(List<Long> accountIds);

    List<Transaction> findByAccountIdAndYearMonth(Long accountId, YearMonth yearMonth);

    List<Transaction> findIncomeByAccountIdBetween(
            Long accountId,
            LocalDateTime start,
            LocalDateTime end
    );

    Optional<Transaction> findByAccountIdAndId(Long accountId, Long transactionId);

    Optional<AccountMonthlySummary> findSummary(Long accountId, YearMonth yearMonth);

    AccountMonthlySummary calculateSummary(Long accountId, YearMonth yearMonth);
}
