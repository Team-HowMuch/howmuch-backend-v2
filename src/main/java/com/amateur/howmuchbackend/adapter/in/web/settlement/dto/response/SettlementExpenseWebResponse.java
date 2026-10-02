package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementExpenseWebResponse(
        UUID expenseId,
        SettlementExpenseSource source,
        String description,
        long amount,
        int displayOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long discount,
        @Schema(description = "영수증 순 배달비. 배달비가 없으면 0원", minimum = "0")
        long deliveryFee
) {
    public static SettlementExpenseWebResponse from(SettlementExpenseResult result) {
        return new SettlementExpenseWebResponse(
                result.expenseId(),
                result.source(),
                result.description(),
                result.amount(),
                result.displayOrder(),
                result.createdAt(),
                result.updatedAt(),
                result.discount(),
                result.deliveryFee()
        );
    }
}
