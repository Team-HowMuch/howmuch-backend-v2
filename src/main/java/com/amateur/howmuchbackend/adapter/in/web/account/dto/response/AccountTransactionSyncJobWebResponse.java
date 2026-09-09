package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionSyncJobResult;
import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record AccountTransactionSyncJobWebResponse(
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
    public static AccountTransactionSyncJobWebResponse from(AccountTransactionSyncJobResult result) {
        return new AccountTransactionSyncJobWebResponse(
                result.syncJobId(),
                result.accountId(),
                result.status(),
                result.fromDate(),
                result.toDate(),
                result.requestedAt(),
                result.completedAt(),
                result.lastSyncedAt(),
                result.failureCode(),
                result.safeMessage()
        );
    }
}
