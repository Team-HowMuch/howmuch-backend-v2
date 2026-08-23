package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;

import java.util.List;

public record SettlementReceiptExpenseMutationResult(
        SettlementExpenseResult expense,
        List<SettlementItemResult> items,
        long totalExpenseAmount
) {
    public static SettlementReceiptExpenseMutationResult of(
            SettlementExpense expense,
            List<SettlementItem> items,
            long totalExpenseAmount
    ) {
        return new SettlementReceiptExpenseMutationResult(
                SettlementExpenseResult.from(expense),
                items.stream().map(SettlementItemResult::from).toList(),
                totalExpenseAmount
        );
    }
}
