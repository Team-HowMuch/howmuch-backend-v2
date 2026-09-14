package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementListItemResult(
        UUID settlementId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        Long totalAmount,
        SettlementAllocationType allocationType,
        Integer participantCount,
        Integer payableParticipantCount,
        Integer completedParticipantCount,
        Long amountToPay,
        String requesterName,
        List<GroupSummary> groups
) {
    public record GroupSummary(
            Long groupId,
            String groupName,
            String imageVersion
    ) {
    }
}
