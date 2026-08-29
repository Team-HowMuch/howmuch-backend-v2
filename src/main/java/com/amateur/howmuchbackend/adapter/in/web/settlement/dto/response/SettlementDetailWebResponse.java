package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {
        "settlementId", "title", "totalAmount", "allocationType", "status", "createdAt",
        "viewerRole", "creatorName", "paymentSummary", "participants"
})
public record SettlementDetailWebResponse(
        UUID settlementId,
        String title,
        long totalAmount,
        @Schema(nullable = true)
        SettlementAllocationType allocationType,
        SettlementStatus status,
        LocalDateTime createdAt,
        SettlementDetailResult.ViewerRole viewerRole,
        String creatorName,
        PaymentSummary paymentSummary,
        List<ParticipantPayment> participants
) {
    public static SettlementDetailWebResponse from(SettlementDetailResult result) {
        return new SettlementDetailWebResponse(
                result.settlementId(),
                result.title(),
                result.totalAmount(),
                result.allocationType(),
                result.status(),
                result.createdAt(),
                result.viewerRole(),
                result.creatorName(),
                PaymentSummary.from(result.paymentSummary()),
                result.participants().stream()
                        .map(ParticipantPayment::from)
                        .toList()
        );
    }

    @Schema(requiredProperties = {
            "expectedAmount", "confirmedAmount", "unpaidAmount", "progressPercent",
            "participantCount", "completedParticipantCount"
    })
    public record PaymentSummary(
            long expectedAmount,
            long confirmedAmount,
            long unpaidAmount,
            int progressPercent,
            int participantCount,
            int completedParticipantCount
    ) {
        private static PaymentSummary from(SettlementDetailResult.PaymentSummary result) {
            return new PaymentSummary(
                    result.expectedAmount(),
                    result.confirmedAmount(),
                    result.unpaidAmount(),
                    result.progressPercent(),
                    result.participantCount(),
                    result.completedParticipantCount()
            );
        }
    }

    @Schema(requiredProperties = {
            "participantId", "displayName", "linked", "expectedAmount", "confirmedAmount", "paymentStatus"
    })
    public record ParticipantPayment(
            UUID participantId,
            String displayName,
            boolean linked,
            @Schema(nullable = true)
            Long expectedAmount,
            long confirmedAmount,
            SettlementDetailResult.PaymentStatus paymentStatus
    ) {
        private static ParticipantPayment from(
                SettlementDetailResult.ParticipantPayment result
        ) {
            return new ParticipantPayment(
                    result.participantId(),
                    result.displayName(),
                    result.linked(),
                    result.expectedAmount(),
                    result.confirmedAmount(),
                    result.paymentStatus()
            );
        }
    }
}
