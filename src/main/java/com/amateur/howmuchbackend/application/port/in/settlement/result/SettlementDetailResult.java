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
        List<ParticipantPayment> participants,
        List<SettlementItemResult> items
) {
    public SettlementDetailResult(
            UUID settlementId, String title, long totalAmount,
            SettlementAllocationType allocationType, SettlementStatus status,
            LocalDateTime createdAt, ViewerRole viewerRole, String creatorName,
            String bankName, String accountNumber, String institutionIconUrl,
            PaymentInfo paymentInfo, PaymentSummary paymentSummary,
            LocalDateTime reminderNextAvailableAt, List<ParticipantPayment> participants
    ) {
        this(settlementId, title, totalAmount, allocationType, status, createdAt,
                viewerRole, creatorName, bankName, accountNumber, institutionIconUrl,
                paymentInfo, paymentSummary, reminderNextAvailableAt, participants, List.of());
    }

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
            LocalDateTime paidAt,
            long discount
    ) {
        public PaymentInfo(long amount, String storeName, LocalDateTime paidAt) {
            this(amount, storeName, paidAt, 0);
        }
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
            String avatarVersion,
            long discountShare
    ) {
        public ParticipantPayment(
                UUID participantId, String displayName, boolean linked, Long expectedAmount,
                long confirmedAmount, PaymentStatus paymentStatus, String avatarVersion
        ) {
            this(participantId, displayName, linked, expectedAmount,
                    confirmedAmount, paymentStatus, avatarVersion, 0);
        }
    }
}
