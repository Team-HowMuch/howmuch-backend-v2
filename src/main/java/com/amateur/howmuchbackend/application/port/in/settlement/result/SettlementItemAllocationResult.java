package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.List;
import java.util.UUID;

public record SettlementItemAllocationResult(
        UUID settlementId,
        UUID participantId,
        String participantDisplayName,
        SettlementItemAllocationPhase phase,
        long personalAmount,
        int submittedParticipantCount,
        int totalParticipantCount,
        List<SettlementItemAllocationItemResult> items,
        List<UUID> invalidItemIds
) {
    public SettlementItemAllocationResult {
        items = List.copyOf(items);
        invalidItemIds = List.copyOf(invalidItemIds);
    }
}
