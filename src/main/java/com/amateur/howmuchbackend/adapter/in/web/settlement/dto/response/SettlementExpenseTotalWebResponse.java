package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseTotalResult;

public record SettlementExpenseTotalWebResponse(long totalExpenseAmount) {
    public static SettlementExpenseTotalWebResponse from(SettlementExpenseTotalResult result) {
        return new SettlementExpenseTotalWebResponse(result.totalExpenseAmount());
    }
}
