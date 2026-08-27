package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.util.UUID;

public record SettlementDepositSummaryResult(
        UUID settlementId,
        SettlementStatus status,
        long expectedAmount,
        long confirmedAmount,
        int participantCount,
        int completedParticipantCount
) {
}
