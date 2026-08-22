package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantListItemResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;

import java.util.UUID;

public record SettlementParticipantListItemWebResponse(
        UUID participantId,
        SettlementParticipantSource source,
        String publicId,
        String displayName,
        int displayOrder,
        String avatarUrl
) {
    public static SettlementParticipantListItemWebResponse from(
            UUID settlementId,
            SettlementParticipantListItemResult result
    ) {
        return new SettlementParticipantListItemWebResponse(
                result.participantId(),
                result.source(),
                result.publicId(),
                result.displayName(),
                result.displayOrder(),
                result.hasAvatar()
                        ? "/api/v2/settlements/%s/participants/%s/avatar".formatted(
                                settlementId,
                                result.participantId()
                        )
                        : null
        );
    }
}
