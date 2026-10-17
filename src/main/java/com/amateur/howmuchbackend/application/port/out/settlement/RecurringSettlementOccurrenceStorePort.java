package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementOccurrence;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringSettlementOccurrenceStorePort {
    Optional<RecurringSettlementOccurrence> findById(UUID id);

    Optional<RecurringSettlementOccurrence> findBySchedule(UUID id, LocalDateTime scheduledFor);

    void save(RecurringSettlementOccurrence occurrence);

    List<RecurringSettlementOccurrence> findByRecurringSettlementId(UUID id, UUID cursor, int limit);
}
