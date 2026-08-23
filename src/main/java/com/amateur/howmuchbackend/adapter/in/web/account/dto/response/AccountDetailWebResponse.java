package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountDetailResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

public record AccountDetailWebResponse(
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
        LocalDateTime lastUsedAt
) {
    public static AccountDetailWebResponse from(AccountDetailResult result) {
        return new AccountDetailWebResponse(
                result.id(), result.organization(), result.institutionName(), result.institutionIconUrl(),
                result.name(), result.maskedAccountNumber(), result.isDefault(), result.displayOrder(),
                result.yearMonth(), result.monthlyExpenseAmount(), result.expenseCount(), result.lastUsedAt()
        );
    }
}
