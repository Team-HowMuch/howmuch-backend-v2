package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantSelectionResult;

import java.util.UUID;

public interface SelectSettlementLinkParticipantUseCase {
    SettlementLinkParticipantSelectionResult select(String rawToken, UUID participantId);
}
