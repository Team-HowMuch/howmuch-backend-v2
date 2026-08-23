package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.UUID;

public record SettlementDutchPayParticipantResult(
        UUID participantId,
        String displayName,
        long amount,
        boolean creator
) {
}
