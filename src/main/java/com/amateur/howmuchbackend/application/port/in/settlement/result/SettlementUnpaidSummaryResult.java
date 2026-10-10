package com.amateur.howmuchbackend.application.port.in.settlement.result;

public record SettlementUnpaidSummaryResult(
        long remainingReceivableAmount,
        int unpaidParticipantCount,
        int unconfirmedSettlementCount
) {
}
