package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementDutchPayAllocationsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDutchPayResult;

import java.util.UUID;

public interface ReplaceSettlementDutchPayAllocationsUseCase {
    SettlementDutchPayResult replace(
            UUID settlementId,
            ReplaceSettlementDutchPayAllocationsCommand command
    );
}
