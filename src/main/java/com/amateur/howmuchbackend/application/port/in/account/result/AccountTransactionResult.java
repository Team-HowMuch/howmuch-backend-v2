package com.amateur.howmuchbackend.application.port.in.account.result;

import com.amateur.howmuchbackend.domain.account.AccountTransaction;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountTransactionResult(
        Long id,
        AccountTransactionType type,
        BigDecimal amount,
        LocalDateTime transactedAt,
        String description,
        BigDecimal afterBalance
) {
    public static AccountTransactionResult from(AccountTransaction transaction) {
        return new AccountTransactionResult(
                transaction.id(), transaction.type(), transaction.amount(), transaction.transactedAt(),
                transaction.description(), transaction.afterBalance()
        );
    }
}
