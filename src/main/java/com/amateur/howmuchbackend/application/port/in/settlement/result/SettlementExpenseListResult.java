package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;

import java.util.List;

public record SettlementExpenseListResult(
        List<SettlementExpenseResult> expenses,
        long totalExpenseAmount
) {
    public static SettlementExpenseListResult from(List<SettlementExpense> expenses) {
        List<SettlementExpenseResult> results = expenses.stream()
                .map(SettlementExpenseResult::from)
                .toList();
        long totalAmount = expenses.stream()
                .mapToLong(SettlementExpense::amount)
                .reduce(0L, Math::addExact);
        return new SettlementExpenseListResult(results, totalAmount);
    }
}
