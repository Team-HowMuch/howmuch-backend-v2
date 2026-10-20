package com.amateur.howmuchbackend.application.port.in.account.result;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

public record AccountTransactionSyncResult(
        Long accountId,
        YearMonth yearMonth,
        boolean refreshed,
        boolean syncInProgress,
        LocalDateTime syncedAt,
        LocalDate fetchedFrom,
        LocalDate fetchedTo,
        int fetchedCount,
        BigDecimal monthlyExpenseAmount,
        long expenseCount,
        LocalDateTime lastUsedAt
) {
}
