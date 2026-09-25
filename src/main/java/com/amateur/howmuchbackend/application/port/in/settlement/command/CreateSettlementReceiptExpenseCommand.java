package com.amateur.howmuchbackend.application.port.in.settlement.command;

import java.time.LocalDateTime;
import java.util.List;

public record CreateSettlementReceiptExpenseCommand(
        String receiptReference,
        String storeName,
        LocalDateTime paidAt,
        List<Item> items,
        long discount
) {
    public CreateSettlementReceiptExpenseCommand(
            String receiptReference, String storeName, LocalDateTime paidAt, List<Item> items
    ) {
        this(receiptReference, storeName, paidAt, items, 0);
    }

    public record Item(
            String name,
            int quantity,
            long amount,
            long discount,
            List<Option> options
    ) {
        public Item(String name, int quantity, long amount) {
            this(name, quantity, amount, 0, List.of());
        }
    }

    public record Option(String name, long price) {
    }
}
