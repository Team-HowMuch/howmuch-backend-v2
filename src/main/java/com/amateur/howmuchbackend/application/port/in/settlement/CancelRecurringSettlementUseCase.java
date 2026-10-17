package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;
import java.util.UUID;

public interface CancelRecurringSettlementUseCase {
    RecurringSettlementResult cancel(Long groupId, UUID recurringSettlementId);
}
