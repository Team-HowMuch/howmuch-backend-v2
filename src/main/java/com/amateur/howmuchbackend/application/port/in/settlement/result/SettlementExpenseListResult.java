package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;

import java.util.List;

public record SettlementExpenseListResult(
        List<SettlementExpenseResult> expenses,
        long totalExpenseAmount
) {
    public static SettlementExpenseListResult from(
            List<SettlementExpense> expenses,
            long totalExpenseAmount
    ) {
        return new SettlementExpenseListResult(
                expenses.stream()
                        .map(SettlementExpenseResult::from)
                        .toList(),
                totalExpenseAmount
        );
    }
}
