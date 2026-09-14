package com.amateur.howmuchbackend.domain.account;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Transaction(
        Long id,
        Long accountId,
        String externalTransactionKey,
        AccountTransactionType type,
        BigDecimal amount,
        LocalDateTime transactedAt,
        String description,
        BigDecimal afterBalance,
        String description1,
        String description2,
        String description3,
        String description4
) {
    public Transaction(
            Long id,
            Long accountId,
            String externalTransactionKey,
            AccountTransactionType type,
            BigDecimal amount,
            LocalDateTime transactedAt,
            String description,
            BigDecimal afterBalance
    ) {
        this(id, accountId, externalTransactionKey, type, amount, transactedAt, description, afterBalance,
                null, null, null, null);
    }
}
