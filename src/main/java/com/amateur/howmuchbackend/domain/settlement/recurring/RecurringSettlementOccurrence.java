package com.amateur.howmuchbackend.domain.settlement.recurring;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 실제로 발행된 회차 하나. {@code (recurringSettlementId, scheduledFor)}가 회차 식별자다. */
public record RecurringSettlementOccurrence(
        UUID id, UUID recurringSettlementId, LocalDateTime scheduledFor,
        UUID settlementId, LocalDateTime requestedAt
) {
    public RecurringSettlementOccurrence {
        Objects.requireNonNull(id);
        Objects.requireNonNull(recurringSettlementId);
        Objects.requireNonNull(scheduledFor);
        Objects.requireNonNull(settlementId);
        Objects.requireNonNull(requestedAt);
    }
}
