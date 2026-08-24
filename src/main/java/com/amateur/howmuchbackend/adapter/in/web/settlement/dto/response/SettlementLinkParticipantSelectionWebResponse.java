package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantSelectionResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementLinkParticipantSelectionWebResponse(
        UUID settlementId,
        SettlementLinkParticipantWebResponse participant,
        SettlementRequestPhase nextPhase,
        String participantContext,
        LocalDateTime expiresAt
) {
    public static SettlementLinkParticipantSelectionWebResponse from(
            SettlementLinkParticipantSelectionResult result
    ) {
        return new SettlementLinkParticipantSelectionWebResponse(
                result.settlementId(),
                SettlementLinkParticipantWebResponse.from(result.participant()),
                result.nextPhase(),
                result.participantContext(),
                result.expiresAt()
        );
    }
}
