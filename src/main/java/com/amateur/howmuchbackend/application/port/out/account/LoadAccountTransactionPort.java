package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface LoadAccountTransactionPort {
    Map<Long, LocalDateTime> findLastUsedAtByAccountIds(List<Long> accountIds);

    List<Transaction> findByAccountIdAndYearMonth(Long accountId, YearMonth yearMonth);

    List<LinkedSettlement> findLinkedSettlements(
            Long creatorId,
            List<String> externalTransactionKeys
    );

    List<Transaction> findIncomeByAccountIdBetween(
            Long accountId,
            LocalDateTime start,
            LocalDateTime end
    );

    Optional<Transaction> findByAccountIdAndId(Long accountId, Long transactionId);

    Optional<AccountMonthlySummary> findSummary(Long accountId, YearMonth yearMonth);

    AccountMonthlySummary calculateSummary(Long accountId, YearMonth yearMonth);

    record LinkedSettlement(
            String externalTransactionKey,
            UUID settlementId,
            String title,
            SettlementStatus status
    ) {
    }
}
