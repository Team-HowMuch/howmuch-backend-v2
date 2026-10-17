package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementOccurrenceResult;
import java.time.LocalDateTime;
import java.util.UUID;

public record RecurringSettlementOccurrenceWebResponse(UUID id, LocalDateTime scheduledFor,
        UUID settlementId, LocalDateTime requestedAt) {
    public static RecurringSettlementOccurrenceWebResponse from(RecurringSettlementOccurrenceResult value) {
        return new RecurringSettlementOccurrenceWebResponse(value.id(), value.scheduledFor(),
                value.settlementId(), value.requestedAt());
    }
}
