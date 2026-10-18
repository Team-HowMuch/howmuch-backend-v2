package com.amateur.howmuchbackend.application.port.in.account.result;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

public record AccountDetailResult(
        Long id,
        String organization,
        String institutionName,
        String institutionIconUrl,
        String name,
        String maskedAccountNumber,
        boolean isDefault,
        int displayOrder,
        YearMonth yearMonth,
        BigDecimal monthlyExpenseAmount,
        long expenseCount,
        LocalDateTime lastUsedAt,
        boolean synced,
        LocalDateTime lastSyncedAt,
        boolean syncInProgress
) {
}
