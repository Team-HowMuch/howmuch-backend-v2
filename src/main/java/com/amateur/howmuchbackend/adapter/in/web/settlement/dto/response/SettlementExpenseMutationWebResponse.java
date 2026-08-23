package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseMutationResult;

public record SettlementExpenseMutationWebResponse(
        SettlementExpenseWebResponse expense,
        long totalExpenseAmount
) {
    public static SettlementExpenseMutationWebResponse from(
            SettlementExpenseMutationResult result
    ) {
        return new SettlementExpenseMutationWebResponse(
                SettlementExpenseWebResponse.from(result.expense()),
                result.totalExpenseAmount()
        );
    }
}
