package com.amateur.howmuchbackend.application.port.in.settlement.netting.result;

import java.time.LocalDateTime;
import java.util.UUID;

public record NettingReviewResult(
        UUID reviewId,
        UUID nettingId,
        long receiverId,
        long transactionId,
        String reason,
        String status,
        String proof,
        Long resolvedBy,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt) {}
