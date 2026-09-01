package com.amateur.howmuchbackend.application.port.in.settlement.command;

import java.time.LocalDateTime;
import java.util.List;

public record CreateSettlementReceiptExpenseCommand(
        String receiptReference,
        String storeName,
        LocalDateTime paidAt,
        List<Item> items
) {
    public record Item(
            String name,
            int quantity,
            long amount
    ) {
    }
}
