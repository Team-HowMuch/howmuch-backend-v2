package com.amateur.howmuchbackend.application.port.in.account.result;

import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AccountTransactionResult(
        Long id,
        AccountTransactionType type,
        BigDecimal amount,
        LocalDateTime transactedAt,
        String description,
        BigDecimal afterBalance,
        List<SettlementSummary> settlements,
        Display display
) {
    public AccountTransactionResult(
            Long id,
            AccountTransactionType type,
            BigDecimal amount,
            LocalDateTime transactedAt,
            String description,
            BigDecimal afterBalance,
            List<SettlementSummary> settlements
    ) {
        this(
                id, type, amount, transactedAt, description, afterBalance, settlements,
                new Display(description == null ? null : description.strip(), Display.Kind.OTHER)
        );
    }

    public static AccountTransactionResult from(
            Transaction transaction,
            List<SettlementSummary> settlements
    ) {
        return from(transaction, new Display(
                transaction.description() == null ? null : transaction.description().strip(),
                Display.Kind.OTHER
        ), settlements);
    }

    public static AccountTransactionResult from(
            Transaction transaction,
            Display display,
            List<SettlementSummary> settlements
    ) {
        return new AccountTransactionResult(
                transaction.id(), transaction.type(), transaction.amount(), transaction.transactedAt(),
                transaction.description(), transaction.afterBalance(), settlements, display
        );
    }

    public record SettlementSummary(
            UUID settlementId,
            String title,
            SettlementStatus status
    ) {
    }

    public record Display(String title, Kind kind) {
        public enum Kind {
            TRANSFER,
            CARD,
            ATM,
            FEE,
            OTHER
        }
    }
}
