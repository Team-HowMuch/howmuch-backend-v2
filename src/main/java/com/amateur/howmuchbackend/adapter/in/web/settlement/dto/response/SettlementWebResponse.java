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
        Long receivingAccountId,
        @io.swagger.v3.oas.annotations.media.Schema(
                        description =
                                "압축 정산에 포함된 원본은 NETTING_IN_PROGRESS, 최종 정산 완료 후에는 COMPLETED입니다.")
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
                result.receivingAccountId(),
                result.status(),
                result.createdAt()
        );
    }
}
