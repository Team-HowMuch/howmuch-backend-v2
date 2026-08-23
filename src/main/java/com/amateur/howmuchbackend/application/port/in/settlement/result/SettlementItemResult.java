package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementItem;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementItemResult(
        UUID itemId,
        UUID settlementId,
        UUID expenseId,
        String name,
        int quantity,
        long amount,
        int displayOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SettlementItemResult from(SettlementItem item) {
        return new SettlementItemResult(
                item.id(),
                item.settlementId(),
                item.expenseId(),
                item.name(),
                item.quantity(),
                item.amount(),
                item.displayOrder(),
                item.createdAt(),
                item.updatedAt()
        );
    }
}
