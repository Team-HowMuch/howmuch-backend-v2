package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementResult(
        UUID settlementId,
        Long creatorId,
        String title,
        long totalAmount,
        SettlementAllocationType allocationType,
        Long receivingAccountId,
        SettlementStatus status,
        LocalDateTime createdAt
) {
    public SettlementResult(
            UUID settlementId,
            Long creatorId,
            String title,
            long totalAmount,
            SettlementStatus status,
            LocalDateTime createdAt
    ) {
        this(settlementId, creatorId, title, totalAmount, null, null, status, createdAt);
    }

    public SettlementResult(
            UUID settlementId,
            Long creatorId,
            String title,
            long totalAmount,
            SettlementAllocationType allocationType,
            SettlementStatus status,
            LocalDateTime createdAt
    ) {
        this(settlementId, creatorId, title, totalAmount, allocationType, null, status, createdAt);
    }

    public static SettlementResult from(Settlement settlement) {
        return new SettlementResult(
                settlement.id(),
                settlement.creatorId(),
                settlement.title(),
                settlement.totalAmount(),
                settlement.allocationType(),
                settlement.receivingAccountId(),
                settlement.status(),
                settlement.createdAt()
        );
    }
}
