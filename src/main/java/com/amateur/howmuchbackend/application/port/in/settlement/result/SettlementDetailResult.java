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
        String bankName,
        String accountNumber,
        String institutionIconUrl,
        PaymentInfo paymentInfo,
        PaymentSummary paymentSummary,
        LocalDateTime reminderNextAvailableAt,
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

    public record PaymentInfo(
            long amount,
            String storeName,
            LocalDateTime paidAt
    ) {
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
            PaymentStatus paymentStatus,
            boolean hasAvatar
    ) {
    }
}
