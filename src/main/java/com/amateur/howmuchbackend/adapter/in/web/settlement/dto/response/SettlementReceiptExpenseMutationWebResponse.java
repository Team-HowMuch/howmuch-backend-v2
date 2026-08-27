package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;

import java.util.List;

public record SettlementReceiptExpenseMutationWebResponse(
        SettlementExpenseWebResponse expense,
        List<SettlementItemWebResponse> items,
        long totalExpenseAmount
) {
    public static SettlementReceiptExpenseMutationWebResponse from(
            SettlementReceiptExpenseMutationResult result
    ) {
        return new SettlementReceiptExpenseMutationWebResponse(
                SettlementExpenseWebResponse.from(result.expense()),
                result.items().stream().map(SettlementItemWebResponse::from).toList(),
                result.totalExpenseAmount()
        );
    }
}
