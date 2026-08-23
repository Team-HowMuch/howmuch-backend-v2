package com.amateur.howmuchbackend.domain.account;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

public record AccountMonthlySummary(
        Long id,
        Long accountId,
        YearMonth yearMonth,
        BigDecimal monthlyExpenseAmount,
        long expenseCount,
        LocalDateTime lastUsedAt
) {
}
