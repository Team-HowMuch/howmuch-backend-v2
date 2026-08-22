package com.amateur.howmuchbackend.application.port.in.settlement;

import java.util.UUID;

public interface RemoveSettlementParticipantUseCase {
    void remove(UUID settlementId, UUID participantId);
}
