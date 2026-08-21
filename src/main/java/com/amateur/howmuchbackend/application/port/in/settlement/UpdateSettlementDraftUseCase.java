package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDraftCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;

import java.util.UUID;

public interface UpdateSettlementDraftUseCase {
    SettlementResult update(UUID settlementId, UpdateSettlementDraftCommand command);
}
