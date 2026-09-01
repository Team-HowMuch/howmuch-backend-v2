package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementDetailResult(
        UUID settlementId,
        String title,
        long totalAmount,
        SettlementAllocationType allocationType,
        SettlementStatus status,
        LocalDateTime createdAt,
        ViewerRole viewerRole,
        String creatorName,
        PaymentSummary paymentSummary,
        List<ParticipantPayment> participants
) {
    public enum ViewerRole {
        CREATOR,
        PARTICIPANT
    }

    public enum PaymentStatus {
        NOT_READY,
        UNPAID,
        PARTIALLY_PAID,
        PAID
    }

    public record PaymentSummary(
            long expectedAmount,
            long confirmedAmount,
            long unpaidAmount,
            int progressPercent,
            int participantCount,
            int payableParticipantCount,
            int completedParticipantCount
    ) {
    }

    public record ParticipantPayment(
            UUID participantId,
            String displayName,
            boolean linked,
            Long expectedAmount,
            long confirmedAmount,
            PaymentStatus paymentStatus
    ) {
    }
}
