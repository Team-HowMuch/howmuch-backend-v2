package com.amateur.howmuchbackend.application.port.in.settlement.netting.result;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;

import java.time.LocalDateTime;
import java.util.*;

public record GroupNettingResult(
        UUID nettingId,
        long groupId,
        long version,
        String status,
        int openReviews,
        long creatorId,
        String creatorName,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        LocalDateTime supersededAt,
        List<Source> sources,
        List<Transfer> transfers) {
    public record Source(
            UUID settlementId,
            String title,
            String status,
            String requesterName,
            LocalDateTime requestedAt,
            String allocationType,
            long originalAmount,
            long actualDepositAmount,
            long replacedAmount,
            String storeName,
            SettlementCategoryResult category,
            int participantCount,
            int payableParticipantCount,
            int completedParticipantCount) {}

    public record Transfer(
            UUID transferId,
            long payerId,
            long receiverId,
            long amount,
            long confirmedAmount,
            String reference,
            String organization,
            String maskedNumber,
            String payerName,
            String payerPublicId,
            String receiverName,
            String receiverPublicId,
            TransferStatus status,
            LocalDateTime confirmedAt,
            List<UUID> sourceSettlementIds,
            LocalDateTime reminderNextAvailableAt,
            ReminderBlockedReason reminderBlockedReason) {}

    public enum ReminderBlockedReason {
        NETTING_NOT_ACTIVE, DEPOSIT_REVIEW_OPEN, DEPOSIT_RECEIVED
    }

    public enum TransferStatus {
        UNPAID, PARTIAL, PAID
    }
}
