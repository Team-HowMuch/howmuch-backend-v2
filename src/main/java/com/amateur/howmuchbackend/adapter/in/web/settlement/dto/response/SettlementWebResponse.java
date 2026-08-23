package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementWebResponse(
        UUID settlementId,
        Long creatorId,
        String title,
        long totalAmount,
        SettlementAllocationType allocationType,
        SettlementStatus status,
        LocalDateTime createdAt
) {
    public static SettlementWebResponse from(SettlementResult result) {
        return new SettlementWebResponse(
                result.settlementId(),
                result.creatorId(),
                result.title(),
                result.totalAmount(),
                result.allocationType(),
                result.status(),
                result.createdAt()
        );
    }
}
