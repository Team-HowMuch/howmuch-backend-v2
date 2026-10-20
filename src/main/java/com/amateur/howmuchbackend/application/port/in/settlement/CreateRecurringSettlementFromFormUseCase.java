package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.WriteRecurringSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;

public interface CreateRecurringSettlementFromFormUseCase {
    RecurringSettlementResult create(Long groupId, WriteRecurringSettlementCommand command);
}
