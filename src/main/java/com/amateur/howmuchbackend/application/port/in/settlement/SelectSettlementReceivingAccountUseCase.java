package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementReceivingAccountCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;

import java.util.UUID;

public interface SelectSettlementReceivingAccountUseCase {
    SettlementResult select(UUID settlementId, SelectSettlementReceivingAccountCommand command);
}
