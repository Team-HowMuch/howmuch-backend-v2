package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.domain.account.AccountTransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface BankTransactionPort {
    List<BankTransaction> fetch(
            String connectedId,
            String organization,
            String account,
            LocalDate startDate,
            LocalDate endDate
    );

    record BankTransaction(
            AccountTransactionType type,
            BigDecimal amount,
            LocalDateTime transactedAt,
            String description,
            BigDecimal afterBalance
    ) {
    }
}
