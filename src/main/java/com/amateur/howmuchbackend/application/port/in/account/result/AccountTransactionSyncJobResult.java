package com.amateur.howmuchbackend.application.port.in.account.result;

import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncJob;
import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record AccountTransactionSyncJobResult(
        UUID syncJobId,
        Long accountId,
        AccountTransactionSyncStatus status,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        LocalDateTime lastSyncedAt,
        String failureCode,
        String safeMessage
) {
    public static AccountTransactionSyncJobResult from(AccountTransactionSyncJob job) {
        return new AccountTransactionSyncJobResult(
                job.id(),
                job.accountId(),
                job.status(),
                job.fromDate(),
                job.toDate(),
                job.requestedAt(),
                job.completedAt(),
                job.lastSyncedAt(),
                job.failureCode(),
                job.safeMessage()
        );
    }
}
