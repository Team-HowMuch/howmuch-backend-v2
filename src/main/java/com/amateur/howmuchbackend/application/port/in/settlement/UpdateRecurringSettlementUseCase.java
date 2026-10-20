package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.WriteRecurringSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;
import java.util.UUID;

public interface UpdateRecurringSettlementUseCase {
    RecurringSettlementResult update(Long groupId, UUID id, WriteRecurringSettlementCommand command);
}
