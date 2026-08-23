package com.amateur.howmuchbackend.adapter.out.receipt;

import com.amateur.howmuchbackend.application.port.out.settlement.ExtractReceiptItemsPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Profile({"local", "dev"})
public class LocalReceiptExtractionStubAdapter implements ExtractReceiptItemsPort {
    @Override
    public Optional<ReceiptExtraction> extract(Long requesterId, String receiptReference) {
        if (requesterId == null || receiptReference == null || receiptReference.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ReceiptExtraction(
                "OCR 영수증",
                30_000L,
                List.of(
                        new ReceiptItem("아메리카노", 2, 10_000L),
                        new ReceiptItem("샌드위치", 2, 20_000L)
                )
        ));
    }
}
