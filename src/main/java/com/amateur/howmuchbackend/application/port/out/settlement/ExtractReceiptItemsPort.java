package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.List;
import java.util.Optional;

public interface ExtractReceiptItemsPort {
    Optional<ReceiptExtraction> extract(String receiptReference);

    record ReceiptExtraction(
            String description,
            long totalAmount,
            List<ReceiptItem> items
    ) {
    }

    record ReceiptItem(
            String name,
            int quantity,
            long amount
    ) {
    }
}
