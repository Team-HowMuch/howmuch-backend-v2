package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 원본 총액과 현재 사용자의 Share 금액을 제공한다. 목록 표시 금액은 Application에서 결정한다. */
public record SettlementListReadModel(
        UUID settlementId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        LocalDateTime endedAt,
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
