package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;
import java.util.UUID;

public interface GetRecurringSettlementUseCase {
    RecurringSettlementResult get(Long groupId, UUID recurringSettlementId);
}
