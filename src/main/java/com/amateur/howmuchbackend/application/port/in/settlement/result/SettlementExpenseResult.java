package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementExpenseResult(
        UUID expenseId,
        UUID settlementId,
        SettlementExpenseSource source,
        String description,
        long amount,
        int displayOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long discount
) {
    public SettlementExpenseResult(
            UUID expenseId, UUID settlementId, SettlementExpenseSource source,
            String description, long amount, int displayOrder,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
        this(expenseId, settlementId, source, description, amount, displayOrder,
                createdAt, updatedAt, 0);
    }

    public static SettlementExpenseResult from(SettlementExpense expense) {
        return new SettlementExpenseResult(
                expense.id(),
                expense.settlementId(),
                expense.source(),
                expense.description(),
                expense.amount(),
                expense.displayOrder(),
                expense.createdAt(),
                expense.updatedAt(),
                expense.discount()
        );
    }
}
