package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryMode;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementLinkEntryWebResponse(
        UUID settlementId,
        String settlementTitle,
        @Schema(nullable = true, description = "영수증 상호명. 영수증 지출이 한 건일 때만 채워진다.")
        String storeName,
        @Schema(nullable = true, description = "영수증 결제일. 정산 상세의 paymentInfo.paidAt과 같은 값이다.")
        LocalDateTime paidAt,
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
                result.storeName(),
                result.paidAt(),
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
