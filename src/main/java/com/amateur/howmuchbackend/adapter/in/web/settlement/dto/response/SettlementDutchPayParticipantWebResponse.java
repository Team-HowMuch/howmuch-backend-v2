package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDutchPayParticipantResult;

import java.util.UUID;

public record SettlementDutchPayParticipantWebResponse(
        UUID participantId,
        String displayName,
        long amount,
        boolean creator
) {
    public static SettlementDutchPayParticipantWebResponse from(
            SettlementDutchPayParticipantResult result
    ) {
        return new SettlementDutchPayParticipantWebResponse(
                result.participantId(),
                result.displayName(),
                result.amount(),
                result.creator()
        );
    }
}
