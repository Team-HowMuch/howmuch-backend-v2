package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementLinkEntryResult(
        UUID settlementId,
        String settlementTitle,
        String storeName,
        LocalDateTime paidAt,
        LocalDateTime requestedAt,
        long totalAmount,
        int participantCount,
        SettlementRequestPhase allocationPhase,
        SettlementLinkEntryMode entryMode,
        SettlementLinkParticipantResult selectedParticipant,
        List<SettlementLinkParticipantResult> participantCandidates,
        String participantContext,
        LocalDateTime contextExpiresAt
) {
}
