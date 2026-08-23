package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementPaymentExpenseCandidateResult;

import java.time.LocalDateTime;

public record SettlementPaymentExpenseCandidateWebResponse(
        Long transactionId,
        String description,
        long amount,
        LocalDateTime transactedAt,
        boolean imported
) {
    public static SettlementPaymentExpenseCandidateWebResponse from(
            SettlementPaymentExpenseCandidateResult result
    ) {
        return new SettlementPaymentExpenseCandidateWebResponse(
                result.transactionId(),
                result.description(),
                result.amount(),
                result.transactedAt(),
                result.imported()
        );
    }
}
