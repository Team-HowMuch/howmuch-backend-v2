package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementOccurrence;
import java.time.LocalDateTime;
import java.util.UUID;

public record RecurringSettlementOccurrenceResult(UUID id, LocalDateTime scheduledFor, UUID settlementId,
        LocalDateTime requestedAt) {
    public static RecurringSettlementOccurrenceResult from(RecurringSettlementOccurrence value) {
        return new RecurringSettlementOccurrenceResult(value.id(), value.scheduledFor(), value.settlementId(),
                value.requestedAt());
    }
}
