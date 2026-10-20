package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementExecutionResult;
import java.time.LocalDateTime;
import java.util.UUID;

public interface ProcessRecurringSettlementUseCase {
    RecurringSettlementExecutionResult process(UUID recurringSettlementId, LocalDateTime scheduledFor);
}
