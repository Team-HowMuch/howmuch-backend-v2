package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDepositSummaryResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.util.UUID;

public record SettlementDepositSummaryWebResponse(
        UUID settlementId,
        SettlementStatus status,
        long expectedAmount,
        long confirmedAmount,
        int participantCount,
        int completedParticipantCount
) {
    public static SettlementDepositSummaryWebResponse from(
            SettlementDepositSummaryResult result
    ) {
        return new SettlementDepositSummaryWebResponse(
                result.settlementId(),
                result.status(),
                result.expectedAmount(),
                result.confirmedAmount(),
                result.participantCount(),
                result.completedParticipantCount()
        );
    }
}
