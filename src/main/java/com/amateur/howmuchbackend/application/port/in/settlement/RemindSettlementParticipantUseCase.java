package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReminderResult;

import java.util.UUID;

public interface RemindSettlementParticipantUseCase {
    SettlementReminderResult remind(UUID settlementId, UUID participantId);
}
