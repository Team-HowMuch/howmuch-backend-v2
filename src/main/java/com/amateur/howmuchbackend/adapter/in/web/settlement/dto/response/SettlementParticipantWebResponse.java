package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;

import java.util.UUID;

public record SettlementParticipantWebResponse(
        UUID participantId,
        SettlementParticipantSource source,
        String publicId,
        String displayName,
        int displayOrder
) {
    public static SettlementParticipantWebResponse from(SettlementParticipantResult result) {
        return new SettlementParticipantWebResponse(
                result.participantId(),
                result.source(),
                result.publicId(),
                result.displayName(),
                result.displayOrder()
        );
    }
}
