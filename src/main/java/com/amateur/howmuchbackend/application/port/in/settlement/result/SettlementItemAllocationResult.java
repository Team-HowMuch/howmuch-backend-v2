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
        List<UUID> invalidItemIds,
        long discount,
        long discountShare
) {
    public SettlementItemAllocationResult(
            UUID settlementId, UUID participantId, String participantDisplayName,
            SettlementItemAllocationPhase phase, long personalAmount,
            int submittedParticipantCount, int totalParticipantCount,
            List<SettlementItemAllocationItemResult> items, List<UUID> invalidItemIds
    ) {
        this(settlementId, participantId, participantDisplayName, phase, personalAmount,
                submittedParticipantCount, totalParticipantCount, items, invalidItemIds, 0, 0);
    }

    public SettlementItemAllocationResult {
        items = List.copyOf(items);
        invalidItemIds = List.copyOf(invalidItemIds);
    }
}
