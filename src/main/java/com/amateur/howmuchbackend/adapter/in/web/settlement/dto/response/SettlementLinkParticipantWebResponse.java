package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantResult;

import java.util.UUID;

public record SettlementLinkParticipantWebResponse(
        UUID participantId,
        String displayName,
        boolean isRequester,
        Long amount
) {
    public static SettlementLinkParticipantWebResponse from(
            SettlementLinkParticipantResult result
    ) {
        return new SettlementLinkParticipantWebResponse(
                result.participantId(),
                result.displayName(),
                result.isRequester(),
                result.amount()
        );
    }
}
