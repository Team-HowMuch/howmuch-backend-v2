package com.amateur.howmuchbackend.application.port.in.settlement.netting.result;

import java.time.LocalDateTime;
import java.util.*;

public record GroupNettingResult(
        UUID nettingId,
        long groupId,
        long version,
        String status,
        int openReviews,
        LocalDateTime createdAt,
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
            long replacedAmount) {}

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
            String receiverPublicId) {}
}
