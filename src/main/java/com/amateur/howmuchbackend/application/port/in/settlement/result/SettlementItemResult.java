package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementItem;

import java.time.LocalDateTime;
import java.util.List;
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
        LocalDateTime updatedAt,
        long discount,
        List<SettlementItemOptionResult> options
) {
    public SettlementItemResult(
            UUID itemId, UUID settlementId, UUID expenseId, String name,
            int quantity, long amount, int displayOrder,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
        this(itemId, settlementId, expenseId, name, quantity, amount,
                displayOrder, createdAt, updatedAt, 0, List.of());
    }

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
                item.updatedAt(),
                item.discount(),
                item.options().stream().map(SettlementItemOptionResult::from).toList()
        );
    }
}
