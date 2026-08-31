package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantResult;

import java.util.UUID;

public interface RenameSettlementParticipantUseCase {
    SettlementParticipantResult rename(UUID settlementId, UUID participantId, String displayName);
}
