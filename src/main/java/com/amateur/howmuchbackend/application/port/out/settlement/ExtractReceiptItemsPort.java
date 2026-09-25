package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.List;
import java.util.Optional;

public interface ExtractReceiptItemsPort {
    Optional<ReceiptExtraction> extract(Long requesterId, String receiptReference);

    record ReceiptExtraction(
            String description,
            long totalAmount,
            List<ReceiptItem> items,
            long discount
    ) {
        public ReceiptExtraction(String description, long totalAmount, List<ReceiptItem> items) {
            this(description, totalAmount, items, 0);
        }
    }

    record ReceiptItem(
            String name,
            int quantity,
            long amount,
            long discount,
            List<ReceiptOption> options
    ) {
        public ReceiptItem(String name, int quantity, long amount) {
            this(name, quantity, amount, 0, List.of());
        }
    }

    record ReceiptOption(String name, long price) {
    }
}
