package com.amateur.howmuchbackend.application.port.in.settlement.command;

import java.time.LocalDateTime;
import java.util.UUID;

public record RecordRecurringSettlementFailureCommand(UUID recurringSettlementId, LocalDateTime scheduledFor,
        boolean transientFailure, boolean automatic) {
}
