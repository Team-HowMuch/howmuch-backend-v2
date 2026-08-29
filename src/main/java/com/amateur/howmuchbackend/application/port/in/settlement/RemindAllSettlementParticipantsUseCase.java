package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementBulkReminderResult;

import java.util.UUID;

public interface RemindAllSettlementParticipantsUseCase {
    SettlementBulkReminderResult remindAll(UUID settlementId);
}
