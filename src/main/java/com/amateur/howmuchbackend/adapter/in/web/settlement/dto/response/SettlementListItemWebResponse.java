package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementListItemWebResponse(
        UUID settlementId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        Long totalAmount,
        Integer participantCount,
        Integer completedParticipantCount,
        Long amountToPay,
        String requesterName
) {
    public static SettlementListItemWebResponse from(SettlementListItemResult result) {
        return new SettlementListItemWebResponse(
                result.settlementId(),
                result.status(),
                result.requestedAt(),
                result.totalAmount(),
                result.participantCount(),
                result.completedParticipantCount(),
                result.amountToPay(),
                result.requesterName()
        );
    }
}
