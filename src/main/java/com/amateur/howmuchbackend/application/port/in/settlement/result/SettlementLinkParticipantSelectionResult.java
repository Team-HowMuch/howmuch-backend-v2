package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementLinkParticipantSelectionResult(
        UUID settlementId,
        SettlementLinkParticipantResult participant,
        SettlementRequestPhase nextPhase,
        String participantContext,
        LocalDateTime expiresAt
) {
}
