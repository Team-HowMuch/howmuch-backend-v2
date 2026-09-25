package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementItemWebResponse(
        UUID itemId,
        UUID expenseId,
        String name,
        int quantity,
        long amount,
        int displayOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long discount,
        List<SettlementItemOptionWebResponse> options
) {
    public static SettlementItemWebResponse from(SettlementItemResult result) {
        return new SettlementItemWebResponse(
                result.itemId(),
                result.expenseId(),
                result.name(),
                result.quantity(),
                result.amount(),
                result.displayOrder(),
                result.createdAt(),
                result.updatedAt(),
                result.discount(),
                result.options().stream().map(SettlementItemOptionWebResponse::from).toList()
        );
    }
}
