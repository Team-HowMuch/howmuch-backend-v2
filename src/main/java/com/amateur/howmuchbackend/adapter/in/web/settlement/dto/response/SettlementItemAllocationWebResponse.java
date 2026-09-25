package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;

import java.util.List;
import java.util.UUID;

public record SettlementItemAllocationWebResponse(
        UUID settlementId,
        UUID participantId,
        String participantDisplayName,
        SettlementItemAllocationPhase phase,
        long personalAmount,
        int submittedParticipantCount,
        int totalParticipantCount,
        List<SettlementItemAllocationItemWebResponse> items,
        List<UUID> invalidItemIds,
        long discount,
        long discountShare
) {
    public static SettlementItemAllocationWebResponse from(
            SettlementItemAllocationResult result
    ) {
        return new SettlementItemAllocationWebResponse(
                result.settlementId(),
                result.participantId(),
                result.participantDisplayName(),
                result.phase(),
                result.personalAmount(),
                result.submittedParticipantCount(),
                result.totalParticipantCount(),
                result.items().stream()
                        .map(SettlementItemAllocationItemWebResponse::from)
                        .toList(),
                result.invalidItemIds(),
                result.discount(),
                result.discountShare()
        );
    }
}
