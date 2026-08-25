package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementListItemResult(
        UUID settlementId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        Long totalAmount,
        Integer participantCount,
        Integer completedParticipantCount,
        Long amountToPay,
        String requesterName
) {
}
