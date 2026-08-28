package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementListReadModel(
        UUID settlementId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        Long totalAmount,
        Integer participantCount,
        Integer completedParticipantCount,
        Long amountToPay,
        String requesterName,
        List<GroupSummary> groups
) {
    public record GroupSummary(
            Long groupId,
            String groupName,
            boolean hasImage
    ) {
    }
}
