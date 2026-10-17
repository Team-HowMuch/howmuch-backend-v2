package com.amateur.howmuchbackend.application.port.in.settlement;

import java.time.LocalDateTime;
import java.util.UUID;

public interface ReplayRecurringSettlementUseCase {
    void replay(UUID recurringSettlementId, LocalDateTime scheduledFor, boolean recoverStaleExecution);
}
