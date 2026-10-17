package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateRecurringSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;

public interface CreateRecurringSettlementUseCase {
    RecurringSettlementResult create(Long groupId, CreateRecurringSettlementCommand command);
}
