package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementPresentationResult;

public record RecurringSettlementAccountWebResponse(Long id, String bankName, String maskedNumber) {
    public static RecurringSettlementAccountWebResponse from(RecurringSettlementPresentationResult value) {
        if (value == null || value.receivingAccount() == null) return null;
        var account = value.receivingAccount();
        return new RecurringSettlementAccountWebResponse(account.id(), account.bankName(), account.maskedNumber());
    }
}
