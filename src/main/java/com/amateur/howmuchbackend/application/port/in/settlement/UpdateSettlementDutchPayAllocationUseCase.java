package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDutchPayAllocationCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDutchPayResult;

import java.util.UUID;

public interface UpdateSettlementDutchPayAllocationUseCase {
    SettlementDutchPayResult update(
            UUID settlementId,
            UUID participantId,
            UpdateSettlementDutchPayAllocationCommand command
    );
}
