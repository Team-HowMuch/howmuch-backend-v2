package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LoadSettlementReminderFactsPort {
    List<ParticipantFacts> findAllBySettlementIds(Collection<UUID> settlementIds);

    record ParticipantFacts(
            UUID settlementId,
            UUID participantId,
            boolean linkedNonCreator,
            boolean submitted,
            boolean sharePresent,
            long confirmedAmount
    ) {}
}
