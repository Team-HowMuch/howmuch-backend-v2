package com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingResult;

import java.time.LocalDateTime;
import java.util.*;

public record GroupNettingWebResponse(
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

    public static GroupNettingWebResponse from(GroupNettingResult r) {
        return new GroupNettingWebResponse(
                r.nettingId(),
                r.groupId(),
                r.version(),
                r.status(),
                r.openReviews(),
                r.createdAt(),
                r.sources().stream()
                        .map(
                                s ->
                                        new Source(
                                                s.settlementId(),
                                                s.title(),
                                                s.status(),
                                                s.requesterName(),
                                                s.requestedAt(),
                                                s.allocationType(),
                                                s.originalAmount(),
                                                s.actualDepositAmount(),
                                                s.replacedAmount()))
                        .toList(),
                r.transfers().stream()
                        .map(
                                t ->
                                        new Transfer(
                                                t.transferId(),
                                                t.payerId(),
                                                t.receiverId(),
                                                t.amount(),
                                                t.confirmedAmount(),
                                                t.reference(),
                                                t.organization(),
                                                t.maskedNumber(),
                                                t.payerName(),
                                                t.payerPublicId(),
                                                t.receiverName(),
                                                t.receiverPublicId()))
                        .toList());
    }
}
