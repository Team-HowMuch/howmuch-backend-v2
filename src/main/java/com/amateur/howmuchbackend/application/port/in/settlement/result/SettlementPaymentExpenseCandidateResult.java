package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.time.LocalDateTime;

public record SettlementPaymentExpenseCandidateResult(
        Long transactionId,
        String description,
        long amount,
        LocalDateTime transactedAt,
        boolean imported
) {
}
