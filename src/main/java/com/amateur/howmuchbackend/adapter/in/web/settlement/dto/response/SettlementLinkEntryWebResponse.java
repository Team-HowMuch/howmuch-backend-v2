package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryMode;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementLinkEntryWebResponse(
        UUID settlementId,
        String settlementTitle,
        long totalAmount,
        int participantCount,
        SettlementRequestPhase allocationPhase,
        SettlementLinkEntryMode entryMode,
        SettlementLinkParticipantWebResponse selectedParticipant,
        List<SettlementLinkParticipantWebResponse> participantCandidates,
        String participantContext,
        LocalDateTime contextExpiresAt
) {
    public static SettlementLinkEntryWebResponse from(SettlementLinkEntryResult result) {
        return new SettlementLinkEntryWebResponse(
                result.settlementId(),
                result.settlementTitle(),
                result.totalAmount(),
                result.participantCount(),
                result.allocationPhase(),
                result.entryMode(),
                result.selectedParticipant() == null
                        ? null
                        : SettlementLinkParticipantWebResponse.from(result.selectedParticipant()),
                result.participantCandidates().stream()
                        .map(SettlementLinkParticipantWebResponse::from)
                        .toList(),
                result.participantContext(),
                result.contextExpiresAt()
        );
    }
}
