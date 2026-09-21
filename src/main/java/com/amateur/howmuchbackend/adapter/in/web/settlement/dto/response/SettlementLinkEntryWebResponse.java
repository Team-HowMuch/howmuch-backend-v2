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
        @Schema(nullable = true, description = "상호명. 영수증·결제내역 지출을 합쳐 한 건일 때만 채워진다.")
        String storeName,
        @Schema(nullable = true, description = "결제일(UTC, offset 없는 값). 영수증·결제내역 지출을 합쳐 한 건일 때만 채워지며 알 수 없으면 null이다. 정산 상세의 paymentInfo.paidAt과 같은 값이다.")
        LocalDateTime paidAt,
        @Schema(description = "정산 요청 시각(UTC, offset 없는 값). 공유 링크 생성 시각을 사용하며 링크 생성 시각이 없으면 정산 생성 시각을 사용한다.")
        LocalDateTime requestedAt,
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
                result.requestedAt(),
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
