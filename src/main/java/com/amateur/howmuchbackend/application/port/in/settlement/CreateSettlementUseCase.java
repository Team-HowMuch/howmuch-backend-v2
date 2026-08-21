package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;

public interface CreateSettlementUseCase {
    SettlementResult create(CreateSettlementCommand command);
}
