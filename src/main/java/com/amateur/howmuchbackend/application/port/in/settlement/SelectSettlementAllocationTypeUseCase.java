package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementAllocationTypeCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementAllocationTypeResult;

import java.util.UUID;

public interface SelectSettlementAllocationTypeUseCase {
    SettlementAllocationTypeResult select(
            UUID settlementId,
            SelectSettlementAllocationTypeCommand command
    );
}
