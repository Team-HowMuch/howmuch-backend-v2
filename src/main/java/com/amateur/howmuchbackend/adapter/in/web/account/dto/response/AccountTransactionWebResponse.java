package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionResult;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountTransactionWebResponse(
        Long id,
        AccountTransactionType type,
        BigDecimal amount,
        LocalDateTime transactedAt,
        String description,
        BigDecimal afterBalance
) {
    public static AccountTransactionWebResponse from(AccountTransactionResult result) {
        return new AccountTransactionWebResponse(
                result.id(), result.type(), result.amount(), result.transactedAt(), result.description(),
                result.afterBalance()
        );
    }
}
