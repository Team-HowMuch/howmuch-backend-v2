package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.UUID;

public record SettlementLinkParticipantResult(
        UUID participantId,
        String displayName
) {
}
