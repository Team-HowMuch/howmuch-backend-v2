package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record GroupSettlementListItemResult(
        UUID settlementId,
        ViewerRole viewerRole,
        String requesterName,
        String requesterPublicId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        LocalDateTime endedAt,
        LocalDateTime completedAt,
        SettlementAllocationType allocationType,
        long totalAmount,
        int participantCount,
        int payableParticipantCount,
        int completedParticipantCount,
        String storeName,
        SettlementCategoryResult category,
        Long receivableAmount,
        Long amountToPay,
        LocalDateTime reminderNextAvailableAt,
        /** PARTICIPANT가 품목 정산에서 아직 몫을 안 골라 amountToPay가 총액 표시값이면 true. */
        boolean needsItemSelection
) {
    public enum ViewerRole { REQUESTER, PARTICIPANT, NONE }
}
