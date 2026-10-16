package com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.NettingReviewResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record NettingReviewWebResponse(
        UUID reviewId,
        UUID nettingId,
        long receiverId,
        long transactionId,
        String reason,
        String status,
        String proof,
        Long resolvedBy,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt) {
    public static NettingReviewWebResponse from(NettingReviewResult r) {
        return new NettingReviewWebResponse(
                r.reviewId(),
                r.nettingId(),
                r.receiverId(),
                r.transactionId(),
                r.reason(),
                r.status(),
                r.proof(),
                r.resolvedBy(),
                r.createdAt(),
                r.resolvedAt());
    }
}
