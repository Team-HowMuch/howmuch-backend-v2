package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;

public record SettlementExpenseMutationResult(
        SettlementExpenseResult expense,
        long totalExpenseAmount
) {
    public static SettlementExpenseMutationResult of(
            SettlementExpense expense,
            long totalExpenseAmount
    ) {
        return new SettlementExpenseMutationResult(
                SettlementExpenseResult.from(expense),
                totalExpenseAmount
        );
    }
}
