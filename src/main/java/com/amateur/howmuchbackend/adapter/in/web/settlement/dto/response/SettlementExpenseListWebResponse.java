package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseListResult;

import java.util.List;

public record SettlementExpenseListWebResponse(
        List<SettlementExpenseWebResponse> expenses,
        long totalExpenseAmount
) {
    public static SettlementExpenseListWebResponse from(SettlementExpenseListResult result) {
        return new SettlementExpenseListWebResponse(
                result.expenses().stream()
                        .map(SettlementExpenseWebResponse::from)
                        .toList(),
                result.totalExpenseAmount()
        );
    }
}
