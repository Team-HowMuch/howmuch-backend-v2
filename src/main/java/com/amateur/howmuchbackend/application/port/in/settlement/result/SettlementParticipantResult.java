package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;

import java.util.UUID;

public record SettlementParticipantResult(
        UUID participantId,
        SettlementParticipantSource source,
        String publicId,
        String displayName,
        int displayOrder
) {
    public static SettlementParticipantResult of(
            SettlementParticipant participant,
            String publicId
    ) {
        return new SettlementParticipantResult(
                participant.id(),
                participant.source(),
                publicId,
                participant.displayName(),
                participant.displayOrder()
        );
    }
}
